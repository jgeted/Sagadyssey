$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$phase7Java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
if (Test-Path -LiteralPath (Join-Path $phase7Java 'bin\java.exe')) {
    $env:JAVA_HOME = $phase7Java
    $env:Path = "$phase7Java\bin;$env:Path"
}
$reportRoot = Join-Path $projectRoot 'build\reports\phase7'
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
function Invoke-Phase7Step {
    param([string] $Label, [string[]] $GradleArguments, [string] $LogName)
    Write-Host $Label -ForegroundColor Cyan
    & (Join-Path $projectRoot 'gradlew.bat') @GradleArguments '--console=plain'
    $phase7ExitCode = $LASTEXITCODE
    if ($LogName) {
        Copy-Item -LiteralPath (Join-Path $projectRoot 'run\phase4-gametest\logs\latest.log') -Destination (Join-Path $reportRoot $LogName)
    }
    if ($phase7ExitCode -ne 0) { throw "Phase 7 verification failed: $Label (exit $phase7ExitCode)" }
}
Push-Location -LiteralPath $projectRoot
try {
    Invoke-Phase7Step '1/3: All automated tests and complete build' @('build') ''
    Invoke-Phase7Step '2/3: Actual local chapel, donation, review, carrying, gift and knowledge workflows; save original bodies' @('runGameTestServer', '-Pphase4RequireRestart=false') 'baseline.log'
    Invoke-Phase7Step '3/3: New process; mandatory original donation and carrying deacon recovery; all regressions' @('runGameTestServer', '-Pphase4RequireRestart=true') 'restart.log'
    Write-Host 'Phase 7 verification passed. Client worlds were not used. Evidence: build/reports/phase7' -ForegroundColor Green
} finally { Pop-Location }
