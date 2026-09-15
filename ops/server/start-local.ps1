$ErrorActionPreference = 'Stop'
$eula = Join-Path $PSScriptRoot 'eula.txt'
if (-not (Test-Path -LiteralPath $eula) -or -not (Select-String -LiteralPath $eula -Pattern '^\s*eula\s*=\s*true\s*$' -Quiet)) {
    throw 'Read the Minecraft EULA and set eula=true yourself before starting this server.'
}
$version = (& java -version 2>&1 | Out-String)
if ($version -notmatch 'version "25[.\"]') { throw 'Java 25 must be available on PATH.' }
Push-Location $PSScriptRoot
try {
    & java -Xms1G -Xmx2G -jar (Join-Path $PSScriptRoot 'paper.jar') --nogui
    if ($LASTEXITCODE -ne 0) { throw "Paper exited with code $LASTEXITCODE" }
} finally { Pop-Location }
