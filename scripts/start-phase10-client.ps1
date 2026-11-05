param([switch] $Check)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pointer = Join-Path $projectRoot 'build\reports\phase10\client-launch.clixml'
if (-not (Test-Path -LiteralPath $pointer)) { throw 'No verified copy prepared. Run verify-phase10-world-copy.ps1 first.' }
$clientFolder = Import-Clixml -LiteralPath $pointer
if ($clientFolder -isnot [string] -or $clientFolder -notmatch '^phase10-client-[a-f0-9]{32}$') { throw 'Invalid isolated-client folder' }
$clientWorld = Join-Path $projectRoot "run\saves\$clientFolder"
if (-not (Test-Path -LiteralPath (Join-Path $clientWorld 'phase10-copy-audit.nbt'))) { throw 'Verified copy audit receipt missing' }
if (-not (Test-Path -LiteralPath (Join-Path $clientWorld 'level.dat'))) { throw 'Verified client copy missing' }
$phase10Java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
if (-not (Test-Path -LiteralPath (Join-Path $phase10Java 'bin\java.exe'))) { throw 'Java 21 is required' }
$env:JAVA_HOME = $phase10Java
$env:Path = "$phase10Java\bin;$env:Path"
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle' }
Write-Host "Stage 10: entering only the verified COPY: $clientWorld"
Write-Host 'Original world is not selected. Close any old Minecraft client first.'
if ($Check) { & (Join-Path $phase10Java 'bin\java.exe') -version; exit $LASTEXITCODE }
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') runClient "-Pphase10ClientWorld=$clientFolder" '--console=plain'
    $launchExit = $LASTEXITCODE
} finally { Pop-Location }
exit $launchExit
