$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$bundledJava = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
if (Test-Path -LiteralPath (Join-Path $bundledJava 'bin\java.exe')) {
    $env:JAVA_HOME = $bundledJava
    $env:Path = "$bundledJava\bin;$env:Path"
}
function Invoke-VerificationStep {
    param([string] $Label, [string[]] $GradleArguments)
    Write-Host $Label -ForegroundColor Cyan
    & (Join-Path $projectRoot 'gradlew.bat') @GradleArguments '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Verification failed: $Label (exit $LASTEXITCODE)" }
}
Push-Location -LiteralPath $projectRoot
try {
    Invoke-VerificationStep '1/3: All automated tests and complete build' @('build')
    Invoke-VerificationStep '2/3: Real game workflows and saved checkpoint' @('runGameTestServer', '-Pphase4RequireRestart=false')
    Invoke-VerificationStep '3/3: New process, saved-world reload, and all game workflows' @('runGameTestServer', '-Pphase4RequireRestart=true')
    Write-Host 'Phase 4 verification passed. Your client worlds were not used.' -ForegroundColor Green
} finally {
    Pop-Location
}
