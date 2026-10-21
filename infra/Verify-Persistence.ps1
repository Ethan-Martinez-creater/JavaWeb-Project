[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EnvFile,
    [Parameter(Mandatory)][ValidatePattern('^eventflow-(e2e|bench|full)[a-z0-9-]*$')][string]$Project,
    [string]$OutputPath = 'e2e/artifacts/persistence.json'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $PSScriptRoot 'docker-compose.full.yml'
$composeArgs = @('compose', '--env-file', (Resolve-Path -LiteralPath $EnvFile).Path, '-p', $Project, '-f', $composeFile)
function Read-DatabaseSnapshot {
    # Environment expansion is inside the project container; credentials never enter host arguments.
    $sql = "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history WHERE success=1; SELECT COUNT(*) FROM users; SELECT COUNT(*) FROM events; SELECT COUNT(*) FROM orders; SELECT COUNT(*) FROM tickets; SELECT COUNT(*) FROM checkin_records; SELECT COUNT(*) FROM user_notifications;"
    $lines = & docker @composeArgs exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" -D "$MYSQL_DATABASE" -N -B -e "$1"' sh $sql
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read this project database' }
    if ($lines.Count -ne 7 -or $lines[0] -ne '1,2,3') { throw 'Expected Flyway V1/V2/V3 and seven snapshot fields' }
    return [ordered]@{ migrations=$lines[0]; users=[long]$lines[1]; events=[long]$lines[2]; orders=[long]$lines[3]; tickets=[long]$lines[4]; checkins=[long]$lines[5]; notifications=[long]$lines[6] }
}
$before = Read-DatabaseSnapshot
if ($before.events -lt 1 -or $before.orders -lt 1 -or $before.tickets -lt 1 -or $before.checkins -lt 1 -or $before.notifications -lt 1) {
    throw 'Run the real E2E before persistence verification; an empty database is not evidence'
}
& docker @composeArgs restart
if ($LASTEXITCODE -ne 0) { throw 'Restart failed' }
& docker @composeArgs up -d --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) { throw 'Stack did not regain health' }
$after = Read-DatabaseSnapshot
if (($before | ConvertTo-Json -Compress) -ne ($after | ConvertTo-Json -Compress)) { throw 'Data changed across restart; investigate before delivery' }
$result = [ordered]@{ observedAt=[DateTime]::UtcNow.ToString('o'); project=$Project; before=$before; after=$after; passed=$true; operation='restart; no volume removal or schema/data reset' }
$target = Join-Path $repoRoot $OutputPath
New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $target -Encoding utf8
Write-Output "Persistence verified: $OutputPath"
