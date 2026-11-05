$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$phase8Java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
if (Test-Path -LiteralPath (Join-Path $phase8Java 'bin\java.exe')) {
    $env:JAVA_HOME = $phase8Java
    $env:Path = "$phase8Java\bin;$env:Path"
}
$reportRoot = Join-Path $projectRoot 'build\reports\phase8'
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
function Invoke-Phase8Step {
    param([string] $Label, [string[]] $GradleArguments, [string] $LogName)
    Write-Host $Label -ForegroundColor Cyan
    & (Join-Path $projectRoot 'gradlew.bat') @GradleArguments '--console=plain'
    $phase8ExitCode = $LASTEXITCODE
    if ($LogName) {
        Copy-Item -LiteralPath (Join-Path $projectRoot 'run\phase4-gametest\logs\latest.log') -Destination (Join-Path $reportRoot $LogName)
    }
    if ($phase8ExitCode -ne 0) { throw "Phase 8 verification failed: $Label (exit $phase8ExitCode)" }
}
Push-Location -LiteralPath $projectRoot
try {
    Invoke-Phase8Step '1/3: Complete build and all model tests' @('build') ''
    Invoke-Phase8Step '2/3: Actual assemblies, education, copying, care, records, maintenance, local agreements and all regressions; save original bodies' @('runGameTestServer', '-Pphase4RequireRestart=false') 'baseline.log'
    Invoke-Phase8Step '3/3: New process; original scribe and labor/material checkpoint, plus all phase 4-8 regressions' @('runGameTestServer', '-Pphase4RequireRestart=true') 'restart.log'
    Write-Host 'Automated phase 8 verification passed. Client acceptance is still required. Evidence: build/reports/phase8' -ForegroundColor Green
} finally { Pop-Location }
