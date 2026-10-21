[CmdletBinding()]
param(
  [string] $OutputPath = '.build-cache/stage4.env'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$resolvedOutputPath = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
  [System.IO.Path]::GetFullPath($OutputPath)
} else {
  [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputPath))
}
$relativeOutputPath = [System.IO.Path]::GetRelativePath($repositoryRoot, $resolvedOutputPath)
if ([System.IO.Path]::IsPathRooted($relativeOutputPath) -or $relativeOutputPath -eq '..' -or $relativeOutputPath.StartsWith('..' + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::Ordinal)) {
  throw 'OutputPath must stay inside the project workspace.'
}
if (Test-Path -LiteralPath $resolvedOutputPath) {
  throw "Refusing to overwrite the existing environment file: $resolvedOutputPath"
}

$parentDirectory = [System.IO.Path]::GetDirectoryName($resolvedOutputPath)
if (-not (Test-Path -LiteralPath $parentDirectory)) {
  New-Item -ItemType Directory -Path $parentDirectory | Out-Null
}

function New-SecretHex {
  param([int] $ByteCount = 32)
  [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes($ByteCount)).ToLowerInvariant()
}

$environment = @(
  '# Local-only credentials generated for this isolated Stage 4 Compose project.'
  'SPRING_PROFILES_ACTIVE=dev'
  'SITE_PORT=8088'
  'DB_NAME=eventflow'
  'DB_USERNAME=eventflow_app'
  "DB_PASSWORD=$(New-SecretHex)"
  "MYSQL_ROOT_PASSWORD=$(New-SecretHex)"
  "REDIS_PASSWORD=$(New-SecretHex)"
  'RABBITMQ_USERNAME=eventflow_app'
  "RABBITMQ_PASSWORD=$(New-SecretHex)"
  "JWT_SECRET=$(New-SecretHex 48)"
  'DEV_ORGANIZER_EMAIL=stage4-organizer@example.test'
  "DEV_ORGANIZER_PASSWORD=$(New-SecretHex)"
)

$encoding = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($resolvedOutputPath, (($environment -join [Environment]::NewLine) + [Environment]::NewLine), $encoding)
Write-Output "Created local secrets file: $resolvedOutputPath"
