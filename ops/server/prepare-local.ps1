param([string]$Destination = "", [string]$PaperJar = "")
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $Destination) { $Destination = Join-Path $repo 'build/local-server' }
$target = [IO.Path]::GetFullPath($Destination)
$plugin = Join-Path $repo 'heist-paper/build/libs/heist-paper-0.1.0-SNAPSHOT.jar'
$pack = Join-Path $repo 'build/resource-pack/gameheist-brasslock-1.2.zip'
if (-not (Test-Path -LiteralPath $plugin) -or -not (Test-Path -LiteralPath $pack)) {
    throw 'Run .\gradlew.bat build first.'
}
if (Test-Path -LiteralPath $target) { throw "Destination already exists; choose a new empty directory: $target" }
$hash = '1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7'
New-Item -ItemType Directory -Path $target | Out-Null
$server = Join-Path $target 'paper.jar'
if ($PaperJar) {
    Copy-Item -LiteralPath $PaperJar -Destination $server
} else {
    Invoke-WebRequest -Uri "https://fill-data.papermc.io/v1/objects/$hash/paper-26.1.2-74.jar" -OutFile $server
}
if ((Get-FileHash -LiteralPath $server -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {
    throw 'Paper checksum mismatch. Server was not prepared or started.'
}
$plugins = Join-Path $target 'plugins'
New-Item -ItemType Directory -Path $plugins | Out-Null
Copy-Item -LiteralPath $plugin -Destination (Join-Path $plugins 'GameHeist.jar')
Copy-Item -LiteralPath $pack -Destination $target
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-local.ps1') -Destination $target
Set-Content -LiteralPath (Join-Path $target 'eula.txt') -Encoding ascii -Value 'eula=false'
@'
server-ip=127.0.0.1
server-port=25565
online-mode=true
max-players=4
level-name=world
gamemode=adventure
spawn-protection=0
view-distance=6
simulation-distance=4
enable-rcon=false
enable-query=false
motd=GameHeist local practice test
'@ | Set-Content -LiteralPath (Join-Path $target 'server.properties') -Encoding ascii
@'
# Local acceptance run

Requires Java 25 and Minecraft Java 26.1.2 clients.
Read https://aka.ms/MinecraftEULA and accept it yourself in eula.txt before starting.
Run .\start-local.ps1 from this directory. It refuses to start without eula=true.
The server binds to localhost and uses authenticated accounts.
For a second computer, explicitly configure the bind address and firewall for your private test network.

After startup, use the server console: op YOUR_PLAYER_NAME
Both players join; /heist controls explains the current input bindings.
Host: /heist create graybox 4, then both players /heist join INSTANCE_UUID.
Host: /heist start INSTANCE_UUID. Follow docs/manual-verification.md in the repository.
Use /heist drain, wait until safe to stop, then console stop.

The bundled pack ZIP must be installed on both clients to test models/audio.
After the plugin creates its config, set resource-pack.preview-models: true and restart for manual-pack testing.
Default storage is memory; this run does not validate MongoDB persistence.
'@ | Set-Content -LiteralPath (Join-Path $target 'TEST-RUN.md') -Encoding utf8
Write-Output "Prepared $target (Paper SHA-256 verified). EULA remains false; server not started."
