param([string] $Python = 'E:\miniconda3\python.exe')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
# Keep source-name construction compatible with Windows PowerShell 5 reading UTF-8 without BOM.
$sourceName = -join @([char]0x9636, [char]0x6BB5, '7', [char]0x6559, [char]0x5802, [char]0x6D4B, [char]0x8BD5)
$sourceWorld = Join-Path (Join-Path $projectRoot 'run\saves') $sourceName
$copyRun = 'copy-' + [Guid]::NewGuid().ToString('N')
$copyRoot = Join-Path $projectRoot "run\phase10-migration\$copyRun"
$copyWorld = Join-Path $copyRoot 'world'
$reportRoot = Join-Path $projectRoot "build\reports\phase10\$copyRun"
$phase10Java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:JAVA_HOME = $phase10Java
$env:Path = "$phase10Java\bin;$env:Path"
$env:GRADLE_USER_HOME = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
function Get-WorldHashes([string] $World) {
    $root = (Resolve-Path -LiteralPath $World).Path
    @(Get-ChildItem -LiteralPath $root -File -Recurse | ForEach-Object {
        [PSCustomObject]@{ Path = $_.FullName.Substring($root.Length + 1); Hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    } | Sort-Object Path)
}
function Assert-SameHashes($Before, $After, [string] $Label) {
    if (Compare-Object $Before $After -Property Path,Hash) { throw "Hash comparison failed: $Label; all copies retained." }
    Write-Host "PASS $Label ($($Before.Count) files)"
}
if (-not (Test-Path -LiteralPath (Join-Path $sourceWorld 'level.dat'))) { throw 'Original source world missing' }
if (Test-Path -LiteralPath $copyRoot) { throw 'Audit destination must be new, never overwritten' }
if (-not (Test-Path -LiteralPath $Python)) { throw 'Python with nbtlib is required for read-only region inspection' }
New-Item -ItemType Directory -Path $copyRoot,$reportRoot | Out-Null
$sourceBefore = Get-WorldHashes $sourceWorld
$sourceBefore | Export-Clixml -LiteralPath (Join-Path $reportRoot 'source-before.clixml')
Push-Location -LiteralPath $projectRoot
try {
    Copy-Item -LiteralPath $sourceWorld -Destination $copyWorld -Recurse
    Assert-SameHashes $sourceBefore (Get-WorldHashes $copyWorld) 'Complete initial copy equals original source'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'phase10-migration-server.properties') -Destination (Join-Path $copyRoot 'server.properties')
    & $Python (Join-Path $PSScriptRoot 'inspect_phase10_copy.py') prepare $sourceWorld $copyWorld
    if ($LASTEXITCODE -ne 0) { throw 'Copy manifest inspection failed' }
    & (Join-Path $projectRoot 'gradlew.bat') build '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Full build failed; do not launch audit' }
    foreach ($phase in @('apply','restart','rollback')) {
        Write-Host "Complete-world copy audit: $phase" -ForegroundColor Cyan
        & (Join-Path $projectRoot 'gradlew.bat') runServer "-Pphase10MigrationRun=$copyRun" "-Pphase10MigrationPhase=$phase" '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw "Copy audit process failed: $phase" }
        $serverLog = Join-Path $copyRoot 'logs\latest.log'
        Copy-Item -LiteralPath $serverLog -Destination (Join-Path $reportRoot "$phase.log")
        if (-not (Select-String -LiteralPath $serverLog -SimpleMatch "PHASE10_COPY_AUDIT_PASS phase=$phase ")) {
            throw "Copy audit did not pass: $phase; see retained logs (including EULA/startup failures)."
        }
    }
    $inspection = & $Python (Join-Path $PSScriptRoot 'inspect_phase10_copy.py') compare $sourceWorld $copyWorld
    if ($LASTEXITCODE -ne 0) { throw 'Offline whole-copy inventory/domain comparison failed' }
    $inspection | Tee-Object -FilePath (Join-Path $reportRoot 'physical-comparison.json')
    Copy-Item -LiteralPath (Join-Path $copyWorld 'phase10-copy-audit.nbt') -Destination $reportRoot
    $clientFolder = 'phase10-client-' + $copyRun.Substring(5)
    $clientWorld = Join-Path $projectRoot "run\saves\$clientFolder"
    if (Test-Path -LiteralPath $clientWorld) { throw 'Client-copy destination already exists; never overwrite it' }
    Copy-Item -LiteralPath $copyWorld -Destination $clientWorld -Recurse
    Assert-SameHashes (Get-WorldHashes $copyWorld) (Get-WorldHashes $clientWorld) 'Client handoff equals verified rollback copy'
    $clientFolder | Export-Clixml -LiteralPath (Join-Path $projectRoot 'build\reports\phase10\client-launch.clixml')
    Write-Host "COPY MIGRATION PASSED: $copyWorld" -ForegroundColor Green
    Write-Host "CLIENT HANDOFF: $clientWorld (Start-Phase10-Acceptance.cmd)"
    Write-Host "Evidence: $reportRoot"
} finally {
    Pop-Location
    $sourceAfter = Get-WorldHashes $sourceWorld
    $sourceAfter | Export-Clixml -LiteralPath (Join-Path $reportRoot 'source-after.clixml')
    Assert-SameHashes $sourceBefore $sourceAfter 'Original source world unchanged'
}
