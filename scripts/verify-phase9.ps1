$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$phase9Java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
if (Test-Path -LiteralPath (Join-Path $phase9Java 'bin\java.exe')) {
    $env:JAVA_HOME = $phase9Java
    $env:Path = "$phase9Java\bin;$env:Path"
}
if (-not $env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
}
$reportRoot = Join-Path $projectRoot 'build\reports\phase9'
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$phase9RunId = 'pair-' + [Guid]::NewGuid().ToString('N')
$phase9GameTestRoot = Join-Path $projectRoot "run\phase9-gametest\$phase9RunId"
$phase9SoakId = $phase9RunId + '-soak'
Write-Host "Isolated baseline/restart pair: $phase9GameTestRoot" -ForegroundColor Cyan
function Invoke-Phase9Step {
    param([string] $Label, [string[]] $GradleArguments, [string] $LogName, [string] $WorldRoot = $phase9GameTestRoot)
    Write-Host $Label -ForegroundColor Cyan
    & (Join-Path $projectRoot 'gradlew.bat') @GradleArguments '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Phase 9 verification failed: $Label (exit $LASTEXITCODE)" }
    if ($LogName) {
        Copy-Item -LiteralPath (Join-Path $WorldRoot 'logs\latest.log') -Destination (Join-Path $reportRoot $LogName)
    }
}
Push-Location -LiteralPath $projectRoot
try {
    Invoke-Phase9Step '1/4: Complete build and all model tests' @('build') ''
    Invoke-Phase9Step '2/4: Real commands, escort, medicine, combat and recovery; save original bodies' @('runGameTestServer', '-Pphase4RequireRestart=false', "-Pphase9GameTestRun=$phase9RunId") 'baseline.log'
    Invoke-Phase9Step '3/4: Independent process; original actors, progress, consent and previous phase checkpoints' @('runGameTestServer', '-Pphase4RequireRestart=true', "-Pphase9GameTestRun=$phase9RunId") 'restart.log'
    Invoke-Phase9Step '4/4: Separate world; three actual game days with bounded planning samples' @('runGameTestServer', '-Pphase4RequireRestart=false', '-Pphase9Soak=true', "-Pphase9GameTestRun=$phase9SoakId") 'soak.log' (Join-Path $projectRoot "run\phase9-gametest\$phase9SoakId")
    Write-Host 'All automated Phase 9 checks passed. Final gate: one real client flow, save/exit/re-enter and ordinary-player information boundary. Stage 10 is not started.' -ForegroundColor Yellow
} finally { Pop-Location }
