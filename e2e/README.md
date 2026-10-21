# Stage 4 browser acceptance test

The end-to-end test uses Playwright 1.64.0 and its matching `mcr.microsoft.com/playwright:v1.64.0-noble` image. The container includes the pinned Chromium build and runs against the full Compose stack, so browser versions do not depend on the host. Run it with a new project name each time to keep test data isolated:

```powershell
Set-Location D:\JavaWeb_Project
$e2eProject = 'eventflow-e2e-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
docker compose --env-file .build-cache/stage4.env -p $e2eProject -f infra/docker-compose.full.yml -f infra/docker-compose.e2e.yml up --build --abort-on-container-exit --exit-code-from e2e
$e2eExit = $LASTEXITCODE
if ($e2eExit -ne 0) { throw "Playwright E2E failed with exit code $e2eExit" }
```

The generated `.build-cache/stage4.env` must contain the dev organizer account. The E2E service reads those values as environment variables; the test output and its custom JSON reporter never include passwords, tokens, email addresses, or ticket codes. Screenshots are captured only from the anonymous public event listing. Reports and screenshots are written under `e2e/artifacts/`. Keep the unique Compose project and its volumes when investigating a failure; stopping the stack with `docker compose ... down` preserves them.

For local debugging with an existing Node.js 24 installation, run the same test against a running stack at `http://127.0.0.1:8088`. npm packages and browser binaries stay under the ignored project-local `.build-cache` directory. This optional path downloads the exact Playwright Chromium build into that cache; it does not install a global npm package or system browser:

```powershell
Set-Location D:\JavaWeb_Project\e2e
$cacheRoot = Join-Path (Get-Location).Path '..\.build-cache'
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
