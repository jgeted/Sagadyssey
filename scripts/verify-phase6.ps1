$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$phase6Java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
if (Test-Path -LiteralPath (Join-Path $phase6Java 'bin\java.exe')) {
    $env:JAVA_HOME = $phase6Java
    $env:Path = "$phase6Java\bin;$env:Path"
}
$reportRoot = Join-Path $projectRoot 'build\reports\phase6'
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
function Invoke-Phase6Step {
    param([string] $Label, [string[]] $GradleArguments, [string] $LogName)
    Write-Host $Label -ForegroundColor Cyan
    & (Join-Path $projectRoot 'gradlew.bat') @GradleArguments '--console=plain'
    $phase6ExitCode = $LASTEXITCODE
    if ($LogName) {
        Copy-Item -LiteralPath (Join-Path $projectRoot 'run\phase4-gametest\logs\latest.log') -Destination (Join-Path $reportRoot $LogName)
    }
    if ($phase6ExitCode -ne 0) { throw "Phase 6 verification failed: $Label (exit $phase6ExitCode)" }
}
Push-Location -LiteralPath $projectRoot
try {
    Invoke-Phase6Step '1/3: All automated tests and complete build' @('build') ''
    Invoke-Phase6Step '2/3: Actual supply, production, carrying and trade workflows; save checkpoints' @('runGameTestServer', '-Pphase4RequireRestart=false') 'baseline.log'
    Invoke-Phase6Step '3/3: New process; mandatory same-body carrying reload and all workflows' @('runGameTestServer', '-Pphase4RequireRestart=true') 'restart.log'
    Write-Host 'Phase 6 verification passed. Client worlds were not used. Evidence: build/reports/phase6' -ForegroundColor Green
} finally { Pop-Location }
