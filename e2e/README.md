# Stage 4 browser acceptance test

The end-to-end test uses Playwright 1.64.0 and its matching `mcr.microsoft.com/playwright:v1.64.0-noble` image. The container includes the pinned Chromium build and runs against the full Compose stack, so browser versions do not depend on the host. Run it with a new project name each time to keep test data isolated:

```powershell
Set-Location D:\JavaWeb_Project
$e2eProject = 'eventflow-e2e-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
docker compose --env-file .build-cache/stage4.env -p $e2eProject -f infra/docker-compose.full.yml up --build -d --wait --wait-timeout 300
if ($LASTEXITCODE -ne 0) { throw 'Full stack is not healthy' }
docker compose --env-file .build-cache/stage4.env -p $e2eProject -f infra/docker-compose.full.yml -f infra/docker-compose.e2e.yml run --build --rm e2e
$e2eExit = $LASTEXITCODE
if ($e2eExit -ne 0) { throw "Playwright E2E failed with exit code $e2eExit" }
./infra/Verify-Persistence.ps1 -EnvFile .build-cache/stage4.env -Project $e2eProject
docker compose --env-file .build-cache/stage4.env -p $e2eProject -f infra/docker-compose.full.yml stop
```

The generated `.build-cache/stage4.env` must contain the dev organizer account. The E2E service reads those values as environment variables; the test output and its custom JSON reporter never include passwords, tokens, email addresses, or ticket codes. Screenshots are captured only from the anonymous public event listing. Reports and screenshots are written under `e2e/artifacts/`. The persistence helper validates V1/V2/V3 and compares nonempty business record counts across a restart. Keep the unique Compose project and its volumes when investigating a failure; `stop` preserves them.

For local debugging with an existing Node.js 24 installation, run the same test against a running stack at `http://127.0.0.1:8088`. Dependencies are installed in ignored `e2e/node_modules`; npm caches, browser binaries and temporary browser profiles stay under the project-local `.build-cache` directory. This optional path downloads the exact Playwright Chromium build into that cache; it does not install a global npm package or system browser:

```powershell
Set-Location D:\JavaWeb_Project\e2e
$cacheRoot = Join-Path (Get-Location).Path '..\.build-cache'
New-Item -ItemType Directory -Path (Join-Path $cacheRoot 'tmp') -Force | Out-Null
$env:TEMP = Join-Path $cacheRoot 'tmp'
$env:TMP = $env:TEMP
$environmentValues = Get-Content -LiteralPath (Join-Path $cacheRoot 'stage4.env') -Encoding utf8 | Where-Object { $_ -match '^[^#][^=]*=' } | ConvertFrom-StringData
$env:npm_config_cache = Join-Path $cacheRoot 'npm-cache'
$env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $cacheRoot 'playwright-browsers'
$env:BASE_URL = 'http://127.0.0.1:8088'
$env:E2E_ORGANIZER_EMAIL = $environmentValues.DEV_ORGANIZER_EMAIL
$env:E2E_ORGANIZER_PASSWORD = $environmentValues.DEV_ORGANIZER_PASSWORD
npm ci
npm exec playwright install chromium
npm test
```

Do not print `$environmentValues` or the credential environment variables. The host command expects the five-service deployment to be healthy before the test starts; the Compose command above starts and waits for it automatically.
