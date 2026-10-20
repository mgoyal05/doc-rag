param([switch] $InitializeSchema, [Parameter(ValueFromRemainingArguments = $true)][string[]] $ApplicationArgs)
$ErrorActionPreference = 'Stop'
$libraryRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $libraryRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
}
$exampleJar = Join-Path $libraryRoot 'examples/spring-boot/target/doc-rag-example.jar'
if (!(Test-Path -LiteralPath $exampleJar)) { throw 'Build the library first with ./scripts/build.ps1' }
Push-Location $libraryRoot
try {
    if ($InitializeSchema) { $ApplicationArgs = @('--research.initialize-schema=true') + $ApplicationArgs }
    & java -jar $exampleJar @ApplicationArgs
    exit $LASTEXITCODE
} finally { Pop-Location }
