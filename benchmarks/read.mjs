import autocannon from 'autocannon';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Intentionally restricted to a dedicated local Compose project, never a shared deployment.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const project = process.env.BENCH_PROJECT;
const envFile = process.env.BENCH_ENV_FILE;
const base = new URL(process.env.BENCH_URL ?? 'http://127.0.0.1:8088');
assert.match(project ?? '', /^eventflow-bench-[a-z0-9-]+$/);
assert.ok(envFile, 'BENCH_ENV_FILE must identify the ignored local Compose env file');
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname));
assert.equal(process.env.BENCH_ISOLATED_ACK, 'YES', 'Confirm a newly created isolated benchmark project');
const eventCount = 100;
const rounds = 5;
const connections = 5;
const compose = ['compose', '--env-file', path.resolve(envFile), '-p', project, '-f', path.join(root, 'infra/docker-compose.full.yml')];
function docker(args) {
  return execFileSync('docker', args, { encoding: 'utf8', timeout: 30_000, windowsHide: true }).trim();
}
function redis(...args) {
  // Secret expansion happens inside this project's Redis container, never in host argv/logs.
  return docker([...compose, 'exec', '-T', 'redis', 'sh', '-c', 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw "$@"', 'sh', ...args]);
}
function cacheStats() {
  const fields = Object.fromEntries(redis('INFO', 'stats').split(/\r?\n/).filter(line => line.includes(':')).map(line => line.split(':')));
  return { hits: Number(fields.keyspace_hits), misses: Number(fields.keyspace_misses) };
}
async function api(method, route, body, token) {
  const response = await fetch(new URL(route, base), {
    method, signal: AbortSignal.timeout(10_000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `Setup failed: ${method} ${route}: HTTP ${response.status}`);
  return response.json();
}
function percentile(samples, percent) {
  const sorted = [...samples].sort((a, b) => a - b);
  return Number(sorted[Math.max(0, Math.ceil(sorted.length * percent) - 1)].toFixed(3));
}
async function measure(ids, amount) {
  const samples = [];
  let next = 0;
  const result = await new Promise((resolve, reject) => {
    const run = autocannon({
      url: base.origin, connections, pipelining: 1, amount, timeout: 10, bailout: 1,
      requests: [{ method: 'GET', path: '/', setupRequest(request) {
        request.path = `/api/v1/events/${ids[next++ % ids.length]}`;
        return request;
      } }],
      verifyBody(body) {
        try {
          const event = JSON.parse(body);
          return ids.includes(event.id) && event.status === 'PUBLISHED' &&
            event.sessions.length === 1 && event.sessions[0].ticketSkus[0].availableStock === 100;
        } catch { return false; }
      },
    }, (error, value) => error ? reject(error) : resolve(value));
    const deadline = setTimeout(() => run.stop(), 60_000);
    run.on('done', () => clearTimeout(deadline));
    run.on('response', (_client, status, _bytes, latency) => {
      if (status === 200) samples.push(latency);
      else run.stop();
    });
  });
  assert.equal(result.errors + result.non2xx + result.mismatches, 0, 'Stop on HTTP/transport/body failure');
  assert.equal(samples.length, amount, 'Partial run is not a successful benchmark');
  return { samples, autocannon: result };
}

const startedAt = new Date().toISOString();
const services = docker([...compose, 'ps', '--services', '--filter', 'status=running']).split(/\r?\n/);
for (const service of ['mysql', 'redis', 'rabbitmq', 'backend', 'frontend']) assert.ok(services.includes(service), `${service} not running`);
assert.equal(docker([...compose, 'port', 'frontend', '8080']), `127.0.0.1:${base.port || '80'}`,
  'BENCH_URL must be the loopback port actually published by this isolated Compose project');
const organizer = await api('POST', '/api/v1/auth/login', {
  email: process.env.DEV_ORGANIZER_EMAIL, password: process.env.DEV_ORGANIZER_PASSWORD,
});
const owned = await api('GET', '/api/v1/organizer/events?size=1', undefined, organizer.accessToken);
assert.equal(owned.total, 0, 'Benchmark requires a fresh isolated project; never clears existing data');
const ids = [];
for (let index = 0; index < eventCount; index++) {
  const event = await api('POST', '/api/v1/organizer/events', { title: `性能实验活动 ${index + 1}`, description: '隔离项目固定数据集；每场 1 个场次、1 个票种、100 库存。' }, organizer.accessToken);
  const session = await api('POST', `/api/v1/organizer/events/${event.id}/sessions`, {
    title: '性能实验场次', startsAt: new Date(Date.now() + 86_400_000).toISOString(),
    endsAt: new Date(Date.now() + 90_000_000).toISOString(), location: '隔离实验环境',
  }, organizer.accessToken);
  await api('POST', `/api/v1/organizer/sessions/${session.id}/ticket-skus`, { name: '标准票', priceCents: 1000, totalStock: 100 }, organizer.accessToken);
  await api('POST', `/api/v1/organizer/events/${event.id}/publish`, undefined, organizer.accessToken);
  ids.push(event.id);
}
// Warm JVM, JDBC pool, TCP path and SQL pages; the comparison isolates Redis metadata cache misses.
for (const id of ids) await api('GET', `/api/v1/events/${id}`);
const phases = [];
for (const cold of [true, false]) {
  const before = cacheStats();
  const runs = [];
  for (let round = 0; round < rounds; round++) {
    // No DEL/FLUSH: invalidate only this private cache namespace. Prior generations expire naturally.
    if (cold) redis('INCR', 'eventflow:events:generation');
    runs.push(await measure(ids, eventCount));
  }
  const after = cacheStats();
  const samples = runs.flatMap(run => run.samples);
  const misses = after.misses - before.misses;
  assert.equal(misses, cold ? eventCount * rounds : 0, 'Observed cache misses must match the controlled experiment');
  phases.push({ name: cold ? 'cold_metadata' : 'warm_metadata', requests: samples.length, connections,
    p50Ms: percentile(samples, 0.50), p95Ms: percentile(samples, 0.95), errorRate: 0,
    durationSeconds: runs.reduce((sum, run) => sum + run.autocannon.duration, 0),
    redis: { keyspaceHits: after.hits - before.hits, keyspaceMisses: misses,
      inferredDetailHits: samples.length - misses, detailHitRate: (samples.length - misses) / samples.length },
    runs,
  });
}
const result = { startedAt, finishedAt: new Date().toISOString(), tool: 'autocannon 8.0.0',
  project, url: base.origin, eventCount, sessionsPerEvent: 1, skusPerSession: 1, stockPerSku: 100,
  method: '5 rounds × 100 requests, 5 connections, unique detail per round. Cold: generation advanced before each round. Warm: same IDs remain cached. JVM/SQL prewarmed. Setup and invalidation excluded from latency. Nearest-rank p50/p95 from individual responseTime samples.',
  limitations: 'Local closed-loop experiment, not maximum throughput or production capacity. All detail requests still query current MySQL stock. Redis INFO counts GETs including generation reads; inferred detail hits assume no other traffic in this private project.',
  environment: { node: process.version, hostOS: `${os.type()} ${os.release()}`, cpu: os.cpus()[0]?.model,
    logicalCpus: os.cpus().length, totalMemoryBytes: os.totalmem(),
    docker: docker(['info', '--format', '{{.ServerVersion}}|{{.NCPU}}|{{.MemTotal}}|{{.OperatingSystem}}']),
    services: JSON.parse(docker([...compose, 'images', '--format', 'json'])),
  }, phases,
};
const output = path.join(root, 'benchmarks/results/stage-4-read-local.json');
await mkdir(path.dirname(output), { recursive: true });
await writeFile(output, JSON.stringify(result, null, 2) + '\n', 'utf8');
console.table(phases.map(({ name, requests, p50Ms, p95Ms, errorRate, redis }) => ({ name, requests, p50Ms, p95Ms, errorRate, detailHitRate: redis.detailHitRate })));
console.log(`Raw results: ${output}`);
