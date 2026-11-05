$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$phase10Java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
if (Test-Path -LiteralPath (Join-Path $phase10Java 'bin\java.exe')) {
    $env:JAVA_HOME = $phase10Java
    $env:Path = "$phase10Java\bin;$env:Path"
}
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle' }
$phase10Run = 'phase10-' + [Guid]::NewGuid().ToString('N')
$reportRoot = Join-Path $projectRoot "build\reports\phase10\$phase10Run"
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
function Invoke-Phase10Step {
    param([string] $Label, [string[]] $Arguments, [string] $Run, [string] $LogName)
    Write-Host $Label -ForegroundColor Cyan
    & (Join-Path $projectRoot 'gradlew.bat') @Arguments '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Stage 10 failed: $Label (exit $LASTEXITCODE). All worlds and logs retained." }
    if ($Run) { Copy-Item -LiteralPath (Join-Path $projectRoot "run\phase9-gametest\$Run\logs\latest.log") -Destination (Join-Path $reportRoot $LogName) }
}
Push-Location -LiteralPath $projectRoot
try {
    Invoke-Phase10Step '1/4 Full build, all unit tests and frozen legacy domain migration' @('build') '' ''
    $unitReports = Join-Path $reportRoot 'unit-tests'
    New-Item -ItemType Directory -Path $unitReports -Force | Out-Null
    Copy-Item -Path (Join-Path $projectRoot 'build\test-results\test\TEST-*.xml') -Destination $unitReports
    Invoke-Phase10Step '2/4 Genuine chunk unload, original migration and all prior-stage baselines' @('runGameTestServer','-Pphase4RequireRestart=false',"-Pphase9GameTestRun=$phase10Run") $phase10Run 'baseline.log'
    Invoke-Phase10Step '3/4 Independent JVM: same original actors, inventories, plans and journal' @('runGameTestServer','-Pphase4RequireRestart=true',"-Pphase9GameTestRun=$phase10Run") $phase10Run 'restart.log'
    $soakRun = $phase10Run + '-soak'
    Invoke-Phase10Step '4/4 100 real loaded NPCs, 25 real forge orders, 72000 actual ticks' @('runGameTestServer','-Pphase4RequireRestart=false','-Pphase10Profile=true','-Pphase10Soak=true',"-Pphase9GameTestRun=$soakRun") $soakRun 'soak.log'
    Copy-Item -LiteralPath (Join-Path $projectRoot "run\phase9-gametest\$soakRun\world\phase10-profile.jfr") -Destination (Join-Path $reportRoot 'profile.jfr')
    Write-Host "Automated checks passed. Evidence: $reportRoot" -ForegroundColor Green
    Write-Host 'Final gate remains: actual client unload/return, save/exit/re-enter, ordinary-player projection. Do not mark Stage 10 complete before that evidence.' -ForegroundColor Yellow
} finally { Pop-Location }
