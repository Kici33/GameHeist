param(
    [switch]$Apply,
    [switch]$RunFailureTests,
    [string]$Namespace = 'gameheist'
)
$ErrorActionPreference = 'Stop'

function Require-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) { throw "Required command is missing: $Name" }
}
Require-Command kubectl

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$renderedDirectory = Join-Path $repo 'build\deployment'
New-Item -ItemType Directory -Force -Path $renderedDirectory | Out-Null
$rendered = Join-Path $renderedDirectory 'gameheist-agones.yaml'
kubectl kustomize (Join-Path $repo 'ops\kubernetes') -o $rendered
if ($LASTEXITCODE -ne 0) { throw 'Kustomize rendering failed' }
$content = Get-Content -LiteralPath $rendered -Raw
foreach ($placeholder in @('replace-with-git-sha', 'replace-with-image-digest', '0000000000000000000000000000000000000000', 'assets.example.invalid')) {
    if ($content.Contains($placeholder)) { throw "Release placeholder remains in rendered manifest: $placeholder" }
}
if ($content -match '(?m)^\s*value:\s*["'']?false["'']?\s*$') {
    throw 'EULA remains false; review the EULA and produce an explicit private release overlay before deployment'
}

kubectl api-resources --api-group=agones.dev | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Agones CRDs are unavailable in the current context' }
kubectl api-resources --api-group=allocation.agones.dev | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Agones allocation API is unavailable in the current context' }
$allowed = kubectl auth can-i create gameserverallocations.allocation.agones.dev --namespace $Namespace
if ($LASTEXITCODE -ne 0 -or $allowed.Trim() -ne 'yes') { throw 'Current identity cannot run allocation acceptance' }
kubectl apply --dry-run=server -f $rendered | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Server-side manifest validation failed' }

if (-not $Apply) {
    Write-Output "Validated against current cluster without mutation: $rendered"
    exit 0
}

kubectl apply -f $rendered | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Fleet deployment failed' }
$deadline = [DateTimeOffset]::UtcNow.AddMinutes(10)
do {
    $fleet = kubectl get fleet gameheist-normal-v4 -n $Namespace -o json | ConvertFrom-Json
    $ready = [int]$fleet.status.readyReplicas
    if ($ready -eq 2) { break }
    Start-Sleep -Seconds 2
} while ([DateTimeOffset]::UtcNow -lt $deadline)
if ($ready -ne 2) { throw "Fleet did not reach two Ready GameServers; ready=$ready" }

$listing = kubectl get gameservers -n $Namespace -l agones.dev/fleet=gameheist-normal-v4 -o json | ConvertFrom-Json
$before = @($listing.items)
if ($before.Count -ne 2 -or @($before | Where-Object { $_.status.state -ne 'Ready' }).Count -ne 0) {
    throw 'Expected exactly two independent Ready GameServers before allocation'
}
$allocated = @()
for ($i = 0; $i -lt 2; $i++) {
    $reservation = [guid]::NewGuid().ToString()
    $request = (Get-Content (Join-Path $repo 'ops\kubernetes\allocation.yaml') -Raw).
        Replace('replace-with-git-sha', (git -C $repo rev-parse HEAD)).
        Replace('replace-with-reservation-uuid', $reservation)
    $result = $request | kubectl create -f - -o json | ConvertFrom-Json
    if ($result.status.state -ne 'Allocated') { throw "Allocation $i failed: $($result.status.state)" }
    $allocated += $result.status.gameServerName
}
if (($allocated | Select-Object -Unique).Count -ne 2) { throw 'Two allocations returned the same GameServer' }
foreach ($name in $allocated) {
    $server = kubectl get gameserver $name -n $Namespace -o json | ConvertFrom-Json
    if ($server.status.state -ne 'Allocated') { throw "$name is not Allocated" }
    $pod = kubectl get pod $name -n $Namespace -o json | ConvertFrom-Json
    if ($pod.spec.automountServiceAccountToken -ne $false -or $pod.spec.serviceAccountName -ne 'gameheist-backend') {
        throw "$name has unexpected Kubernetes credentials"
    }
    $readyProbe = $pod.spec.containers | Where-Object name -eq 'gameheist' | Select-Object -ExpandProperty readinessProbe
    if ($readyProbe.httpGet.path -ne '/ready') { throw "$name has no readiness gate" }
}

if ($RunFailureTests) {
    $victim = $allocated[0]
    kubectl delete pod $victim -n $Namespace --wait=false | Out-Null
    Write-Warning 'A live allocated pod was intentionally deleted. Transparent match restoration is unsupported; verify lobby-visible recovery and Mongo reservation release manually.'
}
Write-Output "Two-pod allocation verified: $($allocated -join ', ')"
