param(
    [ValidateSet('Campaign', 'Opening', 'Battle', 'Skirmish')][string]$Scenario = 'Campaign',
    [ValidateSet('A', 'B', 'C', 'AB', 'AC', 'BC', 'ABC')][string]$Players = 'B',
    [ValidatePattern('^[a-zA-Z0-9-]{1,55}$')][string]$Resume,
    [string]$SeedWorld,
    [switch]$Setup,
    [switch]$Check,
    [switch]$ServerSmoke,
    [switch]$OnlineBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path $root 'run\war-foreground'
$sessions = Join-Path $work 'sessions'
$owner = 'Sagadyssey foreground military test v1'
$requiredFiles = @('level.dat', 'data\sagadyssey_characters.dat', 'data\sagadyssey_war.dat',
                   'data\sagadyssey_war_muster.sgw', 'data\sagadyssey_service_relationships_v2.dat',
                   'campaign-civilians.json')
$properties = Get-Content -LiteralPath (Join-Path $root 'gradle.properties')
$gameVersion = ($properties -match '^minecraft_version=' | Select-Object -First 1) -replace '^minecraft_version=', ''
$neoVersion = ($properties -match '^neo_version=' | Select-Object -First 1) -replace '^neo_version=', ''
if (-not $gameVersion -or -not $neoVersion) { throw 'Missing Minecraft or NeoForge version in gradle.properties.' }

function Test-Java21([string]$Path) {
    if (-not $Path -or -not (Test-Path -LiteralPath (Join-Path $Path 'bin\javac.exe') -PathType Leaf)) { return $false }
    $version = (& (Join-Path $Path 'bin\java.exe') --version | Out-String)
    return $version -match '(?m)^(openjdk|java) 21(\.|\s)'
}

function Find-Java21 {
    $javaHomes = @($env:JAVA_HOME)
    $warUserFolder = $env:USERPROFILE
    if (-not $warUserFolder) { $warUserFolder = [Environment]::GetFolderPath('UserProfile') }
    if ($warUserFolder) { $javaHomes += (Join-Path $warUserFolder '.gradle\jdks\jdk21') }
    foreach ($candidateJavaHome in $javaHomes) {
        if (Test-Java21 $candidateJavaHome) { return $candidateJavaHome }
    }
    $pathJava = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($pathJava) {
        $candidateJavaHome = Split-Path -Parent (Split-Path -Parent $pathJava.Source)
        if (Test-Java21 $candidateJavaHome) { return $candidateJavaHome }
    }
    throw 'A JDK 21 installation is required. Set JAVA_HOME to its directory.'
}

function Assert-PlainDirectory([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    if ($item -and (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint))) {
        throw "Expected an ordinary directory: $Path"
    }
}

function Assert-TestWorld([string]$Path) {
    Assert-PlainDirectory $Path
    foreach ($name in $requiredFiles) {
        if (-not (Test-Path -LiteralPath (Join-Path $Path $name) -PathType Leaf)) {
            throw "Missing campaign test file: $Path\$name"
        }
    }
}

function Assert-TestArchive([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing test fixture: $Path" }
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        foreach ($name in $requiredFiles) {
            if (-not $archive.GetEntry($name.Replace('\', '/'))) {
                throw "Missing campaign test file in $Path`: $name"
            }
        }
    } finally { $archive.Dispose() }
}

function Copy-TestWorld([string]$Source, [string]$Destination) {
    [IO.Directory]::CreateDirectory($Destination) | Out-Null
    foreach ($entry in Get-ChildItem -LiteralPath $Source -Force) {
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Test world contains a redirected path: $($entry.FullName)"
        }
        $target = Join-Path $Destination $entry.Name
        if ($entry.PSIsContainer) {
            Copy-TestWorld $entry.FullName $target
        } elseif ($entry.Name -ne 'session.lock') {
            [IO.File]::Copy($entry.FullName, $target, $false)
        }
    }
}

function Open-WorldLock([string]$Path) {
    $lockPath = Join-Path $Path 'session.lock'
    if (-not (Test-Path -LiteralPath $lockPath -PathType Leaf)) { return }
    try {
        # Read access is enough to reject an active writer and protect the stopped seed during the copy.
        return [IO.File]::Open($lockPath, 'Open', 'Read', 'None')
    } catch [IO.IOException] {
        throw "The test world is in use. Close its Minecraft server before continuing: $Path"
    }
}

function Get-FileDigest([string]$Path) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $stream = [IO.File]::OpenRead($Path)
        try { return [BitConverter]::ToString($sha.ComputeHash($stream)) }
        finally { $stream.Dispose() }
    } finally { $sha.Dispose() }
}

function Get-LoopbackPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return $listener.LocalEndpoint.Port
    } finally { $listener.Stop() }
}

function Start-Game([string]$Role, [string]$Path, [string]$Java) {
    $manifest = Get-Content -LiteralPath (Join-Path $Path 'launcher.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.mainClass -ne 'net.neoforged.devlaunch.Main') { throw "Unexpected NeoForge launcher for $Role." }

    $process = [Diagnostics.Process]::new()
    $psi = $process.StartInfo
    $psi.FileName = $Java
    $psi.WorkingDirectory = $Path
    $vm = Join-Path $Path 'launcher-vm.txt'
    $program = Join-Path $Path 'launcher-program.txt'
    $psi.Arguments = '"@' + $vm + '" ' + $manifest.mainClass + ' "@' + $program + '"'
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($entry in $manifest.environment.PSObject.Properties) {
        $psi.EnvironmentVariables[$entry.Name] = [string]$entry.Value
    }
    $out = [IO.File]::Open((Join-Path $Path 'stdout.log'), 'Create', 'Write', 'Read')
    $err = $null
    $started = $false
    try {
        $err = [IO.File]::Open((Join-Path $Path 'stderr.log'), 'Create', 'Write', 'Read')
        $started = $process.Start()
        if (-not $started) { throw "Could not start $Role." }
        $outTask = $process.StandardOutput.BaseStream.CopyToAsync($out)
        $errTask = $process.StandardError.BaseStream.CopyToAsync($err)
        return [pscustomobject]@{ Role = $Role; Process = $process; Out = $out; Err = $err; OutTask = $outTask; ErrTask = $errTask }
    } catch {
        if ($started -and -not $process.HasExited) {
            $process.Kill()
            $null = $process.WaitForExit(5000)
        }
        if ($err) { $err.Dispose() }
        $out.Dispose()
        $process.Dispose()
        throw
    }
}

function Close-Game($Game) {
    try {
        if (-not $Game.Process.HasExited) { $Game.Process.Kill() }
        $null = $Game.Process.WaitForExit(5000)
        $null = [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($Game.OutTask, $Game.ErrTask), 5000)
    } finally {
        $Game.Out.Dispose()
        $Game.Err.Dispose()
        $Game.Process.Dispose()
    }
}

function Wait-Server($Game, [string]$Path, [int]$Port) {
    $deadline = [datetime]::UtcNow.AddMinutes(3)
    do {
        if ($Game.Process.HasExited) { throw "Server exited during startup. See $Path\stdout.log and stderr.log." }
        $log = Join-Path $Path 'logs\latest.log'
        if ((Test-Path -LiteralPath $log) -and (Select-String -LiteralPath $log -Pattern 'Done \([^)]+\)!' -Quiet)) {
            $probe = [Net.Sockets.TcpClient]::new()
            try {
                if ($probe.ConnectAsync('127.0.0.1', $Port).Wait(500) -and $probe.Connected) { return }
            } catch [AggregateException] {
                # The listener may still be starting.
            } finally { $probe.Dispose() }
        }
        Start-Sleep -Milliseconds 500
    } while ([datetime]::UtcNow -lt $deadline)
    throw "Timed out waiting for the loopback test server. See $Path\logs\latest.log."
}

function Stage-Battle($Game, [string]$Path) {
    foreach ($command in @('saga politics set_relation kingdom church allied',
                            'saga politics set_relation kingdom merchant_guild war',
                            'saga politics set_relation church merchant_guild war')) {
        $Game.Process.StandardInput.WriteLine($command)
    }
    $Game.Process.StandardInput.Flush()
    $log = Join-Path $Path 'logs\latest.log'
    $deadline = [datetime]::UtcNow.AddSeconds(15)
    do {
        if (@(Select-String -LiteralPath $log -Pattern ' = WAR').Count -ge 2) { return }
        Start-Sleep -Milliseconds 300
    } while (-not $Game.Process.HasExited -and [datetime]::UtcNow -lt $deadline)
    throw "Could not stage the battle in the test copy. See $log."
}

if ($Resume -and $SeedWorld) { throw '-Resume already identifies a test world. Omit -SeedWorld.' }
if ($Setup -and $Players -notmatch 'B') { throw '-Setup requires CampaignB in -Players.' }

$javaHome = Find-Java21
foreach ($path in @((Join-Path $root 'run'), $work, $sessions, (Join-Path $work 'build'), (Join-Path $work 'project-cache'))) {
    Assert-PlainDirectory $path
}
$marker = 'session.json'
$seedArchive = $null
if ($Resume) {
    $label = $Resume
    $session = Join-Path $sessions $label
    Assert-PlainDirectory $session
    $receipt = Get-Content -LiteralPath (Join-Path $session $marker) -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($receipt.owner -ne $owner -or $receipt.minecraft -ne $gameVersion -or $receipt.neoforge -ne $neoVersion) {
        throw 'The session belongs to a different launcher or game version.'
    }
    Assert-PlainDirectory (Join-Path $session 'Server')
    Assert-TestWorld (Join-Path $session 'Server\world')
} else {
    if ($SeedWorld) {
        $SeedWorld = [IO.Path]::GetFullPath($SeedWorld)
        Assert-TestWorld $SeedWorld
    } else {
        if ($gameVersion -ne '1.21.11') { throw 'The bundled test fixtures are for Minecraft 1.21.11. Use -SeedWorld to supply a compatible fixture.' }
        $fixtureName = if ($Scenario -eq 'Skirmish') { 'battle' } else { $Scenario.ToLowerInvariant() }
        $seedArchive = Join-Path $PSScriptRoot "war-acceptance-fixtures\$fixtureName.zip"
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        Assert-TestArchive $seedArchive
    }
    $label = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Scenario.ToLowerInvariant() + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
    $session = Join-Path $sessions $label
}

$serverPath = Join-Path $session 'Server'
$worldPath = Join-Path $serverPath 'world'
if ($Check) {
    $checkWorld = if ($Resume) { $worldPath } elseif ($seedArchive) { $seedArchive } else { $SeedWorld }
    Write-Host "Ready to prepare Minecraft $gameVersion / NeoForge $neoVersion with $javaHome"
    Write-Host "Test world: $checkWorld"
    exit 0
}

New-Item -ItemType Directory -Force -Path $sessions | Out-Null
$launchLock = $null
$server = $null
$clients = [Collections.Generic.List[object]]::new()
try {
    try { $launchLock = [IO.File]::Open((Join-Path $work 'launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch [IO.IOException] { throw 'A foreground military session is already running in this checkout.' }

    if ($Resume) {
        $worldLock = Open-WorldLock $worldPath
        if ($worldLock) { $worldLock.Dispose() }
    } else {
        New-Item -ItemType Directory -Path $session | Out-Null
        if ($seedArchive) {
            [IO.Compression.ZipFile]::ExtractToDirectory($seedArchive, $worldPath)
            Assert-TestWorld $worldPath
        } else {
            $worldLock = Open-WorldLock $SeedWorld
            try {
                Copy-TestWorld $SeedWorld $worldPath
                foreach ($name in $requiredFiles) {
                    $original = Get-FileDigest (Join-Path $SeedWorld $name)
                    $copied = Get-FileDigest (Join-Path $worldPath $name)
                    if ($original -ne $copied) { throw "The test copy failed verification: $name" }
                }
            } finally { if ($worldLock) { $worldLock.Dispose() } }
        }
        $source = if ($seedArchive) { $seedArchive } else { $SeedWorld }
        $receipt = [ordered]@{ owner = $owner; minecraft = $gameVersion; neoforge = $neoVersion; source = $source }
        $receipt | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $session $marker) -Encoding UTF8
    }
    $port = Get-LoopbackPort
    $serverProperties = @(
        "server-port=$port", 'server-ip=127.0.0.1', 'online-mode=false', 'enforce-secure-profile=false',
        'level-name=world', 'gamemode=survival', 'difficulty=normal', 'spawn-protection=0',
        'view-distance=5', 'simulation-distance=3', 'max-players=3', 'max-tick-time=120000',
        'allow-flight=true', 'enable-status=true', 'enable-rcon=false', 'enable-query=false', 'enable-command-block=false'
    )
    [IO.File]::WriteAllLines((Join-Path $serverPath 'server.properties'), $serverProperties)
    $operators = @()
    if ($Setup) {
        # The fixed original account is the only operator in an explicitly requested local test setup.
        $operators = @(@{ uuid = 'e7623d51-1580-3eee-a25f-899f8e1e8fc4'; name = 'CampaignB'; level = 4; bypassesPlayerLimit = $false })
        Write-Host 'Scenario setup enabled for CampaignB on this copy.'
    }
    [IO.File]::WriteAllText((Join-Path $serverPath 'ops.json'), (ConvertTo-Json -InputObject $operators -Compress))
    $roleSet = if ($ServerSmoke) { 'server' } else { $Players.ToUpperInvariant() }
    foreach ($role in $Players.ToUpperInvariant().ToCharArray()) {
        if ($ServerSmoke) { break }
        $clientPath = Join-Path $session $role.ToString()
        Assert-PlainDirectory $clientPath
        [IO.Directory]::CreateDirectory($clientPath) | Out-Null
        $options = Join-Path $clientPath 'options.txt'
        if (-not (Test-Path -LiteralPath $options)) {
            [IO.File]::WriteAllLines($options, @('fullscreen:false', 'pauseOnLostFocus:false', 'maxFps:45',
                                               'renderDistance:5', 'simulationDistance:5', 'guiScale:2', 'lang:zh_cn'))
        }
    }

    $env:JAVA_HOME = $javaHome
    $java = Join-Path $javaHome 'bin\java.exe'
    $gradleArgs = @('--no-configuration-cache', '--no-daemon', '--console=plain', '--max-workers=2',
        '--project-cache-dir', (Join-Path $work 'project-cache'), '-I', (Join-Path $PSScriptRoot 'war-foreground.init.gradle'),
        "-PwarForegroundRun=$label", "-PwarForegroundPlayers=$roleSet", "-PwarForegroundPort=$port", 'prepareWarForeground')
    $initialArgs = if ($OnlineBuild) { $gradleArgs } else { @('--offline') + $gradleArgs }
    $buildLog = Join-Path $session 'build.log'
    Write-Host "Preparing $label. Build log: $buildLog"
    Push-Location -LiteralPath $root
    try {
        $ErrorActionPreference = 'Continue'
        $gradlew = Join-Path $root 'gradlew.bat'
        & $gradlew @initialArgs *> $buildLog
        if ($LASTEXITCODE -ne 0 -and -not $OnlineBuild -and (Select-String -LiteralPath $buildLog -Pattern 'offline mode' -Quiet)) {
            Write-Host 'Gradle dependencies are missing locally. Retrying with downloads enabled.'
            & $gradlew @gradleArgs *>> $buildLog
        }
        if ($LASTEXITCODE -ne 0) { throw "Gradle preparation failed. See $buildLog. Use -OnlineBuild if dependencies are missing." }
    } finally {
        $ErrorActionPreference = 'Stop'
        Pop-Location
    }

    $server = Start-Game 'Server' $serverPath $java
    Wait-Server $server $serverPath $port
    if ($Scenario -eq 'Skirmish' -and -not $Resume) { Stage-Battle $server $serverPath }
    Write-Host "Isolated server ready on 127.0.0.1:$port"
    Write-Host "Session: $label"
    Write-Host "Logs and save: $session"
    if (-not $ServerSmoke) {
        foreach ($role in $roleSet.ToCharArray()) {
            $clients.Add((Start-Game $role.ToString() (Join-Path $session $role.ToString()) $java))
        }
        Write-Host 'Quit Minecraft in all opened clients to save and stop the server.'
        while (@($clients | Where-Object { -not $_.Process.HasExited }).Count -gt 0) {
            if ($server.Process.HasExited) { throw "The server stopped while the clients were running. See $serverPath\logs\latest.log." }
            Start-Sleep -Seconds 1
        }
        foreach ($client in $clients) {
            if ($client.Process.ExitCode -ne 0) {
                throw "Campaign$($client.Role) exited with code $($client.Process.ExitCode). See $session\$($client.Role)\logs\latest.log and stderr.log."
            }
        }
    }
} finally {
    try {
        foreach ($client in $clients) {
            try { Close-Game $client }
            catch { Write-Warning "Could not clean up client $($client.Role): $_" }
        }
        if ($server) {
            try {
                if (-not $server.Process.HasExited) {
                    $server.Process.StandardInput.WriteLine('stop')
                    $server.Process.StandardInput.Flush()
                    if (-not $server.Process.WaitForExit(60000)) { throw 'The test server did not finish saving within 60 seconds.' }
                }
                if ($server.Process.ExitCode -ne 0) { throw "Server exited with code $($server.Process.ExitCode). See $serverPath\stdout.log and stderr.log." }
            } finally { Close-Game $server }
        }
    } finally { if ($launchLock) { $launchLock.Dispose() } }
}
if ($ServerSmoke) { Write-Host 'Server smoke passed: loaded the test copy, listened on loopback, saved and stopped.' }
