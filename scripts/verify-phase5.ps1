$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$phase5Java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
if (Test-Path -LiteralPath (Join-Path $phase5Java 'bin\java.exe')) {
    $env:JAVA_HOME = $phase5Java
    $env:Path = "$phase5Java\bin;$env:Path"
}
function Invoke-Phase5Step {
    param([string] $Label, [string[]] $GradleArguments)
    Write-Host $Label -ForegroundColor Cyan
    & (Join-Path $projectRoot 'gradlew.bat') @GradleArguments '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Phase 5 verification failed: $Label (exit $LASTEXITCODE)" }
}
Push-Location -LiteralPath $projectRoot
try {
    Invoke-Phase5Step '1/3: All tests and complete build' @('build')
    Invoke-Phase5Step '2/3: Actual life, social and profession workflows; save checkpoints' @('runGameTestServer', '-Pphase4RequireRestart=false')
    Invoke-Phase5Step '3/3: New process; mandatory meal and profession reload plus all workflows' @('runGameTestServer', '-Pphase4RequireRestart=true')
    Write-Host 'Phase 5 verification passed. Client worlds were not used.' -ForegroundColor Green
} finally { Pop-Location }
