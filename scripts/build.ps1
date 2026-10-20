$ErrorActionPreference = 'Stop'
$buildArguments = @($args)
$libraryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $libraryRoot
try {
    & (Join-Path $libraryRoot 'mvnw.cmd') install @buildArguments
    exit $LASTEXITCODE
} finally { Pop-Location }
