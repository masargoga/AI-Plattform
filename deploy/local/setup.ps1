# Builds the image on this machine, makes sure a local Kubernetes cluster exists, and deploys AgenticPilot to it (Windows PowerShell).
# Needs: Docker Desktop with Kubernetes enabled (Settings > Kubernetes > Enable), or kind. kubectl comes with Docker Desktop.
# Usage (from the repository root):
#   $env:AGENTICPILOT_SEED_PASSWORD = 'choose-a-password'
#   $env:AGENTICPILOT_MODEL_API_KEY = '...'     # optional, for real model answers
#   .\deploy\local\setup.ps1
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')

if (-not $env:AGENTICPILOT_SEED_PASSWORD) { throw 'Set $env:AGENTICPILOT_SEED_PASSWORD first (the password for the users owner, admin and admin2).' }
$Image = 'agenticpilot:local'
$Ns = 'agenticpilot'

function Run {
    # Runs a native command and stops on a non-zero exit code
    param([string]$Command, [string[]]$Arguments)
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command $($Arguments -join ' ') failed (exit code $LASTEXITCODE)" }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker is not installed.' }
if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) { throw 'kubectl is not installed (Docker Desktop installs it when Kubernetes is enabled).' }
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker is not running. Start Docker Desktop first.' }

Write-Host "==> Building $Image (the first build takes several minutes)"
Run docker @('build', '-t', $Image, '.')

$hasKind = [bool](Get-Command kind -ErrorAction SilentlyContinue)
$contexts = @(kubectl config get-contexts -o name)
$desktopUsable = $false
if ($contexts -contains 'docker-desktop') {
    kubectl --context docker-desktop get nodes *> $null
    $desktopUsable = ($LASTEXITCODE -eq 0)
}

if ($desktopUsable) {
    $Context = 'docker-desktop'
    Write-Host '==> Using the Docker Desktop cluster'
    # With Docker Desktop's "kind" provisioner the nodes do not see local images: load it. With "kubeadm" this is not needed.
    $nodes = kubectl --context docker-desktop get nodes -o name
    if (($nodes -match 'desktop-control-plane') -and $hasKind) {
        Run kind @('load', 'docker-image', $Image, '--name', 'desktop')
    }
} else {
    if (-not $hasKind) { throw 'No Kubernetes cluster found. Enable Kubernetes in Docker Desktop (Settings > Kubernetes), or install kind: https://kind.sigs.k8s.io' }
    if (-not ((kind get clusters) -contains 'agenticpilot')) { Run kind @('create', 'cluster', '--name', 'agenticpilot') }
    Run kind @('load', 'docker-image', $Image, '--name', 'agenticpilot')
    $Context = 'kind-agenticpilot'
}

Write-Host "==> Deploying to context $Context"
kubectl --context $Context create namespace $Ns --dry-run=client -o yaml | kubectl --context $Context apply -f -
if ($LASTEXITCODE -ne 0) { throw 'Could not create the namespace.' }

# Secrets come from your environment; they are never written to a file in the repository.
$apiKey = if ($env:AGENTICPILOT_MODEL_API_KEY) { $env:AGENTICPILOT_MODEL_API_KEY } else { '' }
kubectl --context $Context -n $Ns create secret generic agenticpilot-secrets `
    "--from-literal=AGENTICPILOT_SEED_PASSWORD=$($env:AGENTICPILOT_SEED_PASSWORD)" `
    "--from-literal=AGENTICPILOT_MODEL_API_KEY=$apiKey" `
    --dry-run=client -o yaml | kubectl --context $Context apply -f -
if ($LASTEXITCODE -ne 0) { throw 'Could not create the secret.' }

Run kubectl @('--context', $Context, 'apply', '-k', 'deploy/k8s/overlays/local')
# A changed image with the same tag needs a restart
Run kubectl @('--context', $Context, '-n', $Ns, 'rollout', 'restart', 'deploy/agenticpilot')
Run kubectl @('--context', $Context, '-n', $Ns, 'rollout', 'status', 'deploy/agenticpilot', '--timeout=300s')

Write-Host @"

AgenticPilot is running. Open it with:

  kubectl --context $Context -n $Ns port-forward svc/agenticpilot 8080:8080

then browse to http://localhost:8080 and sign in as admin, admin2 or owner with your AGENTICPILOT_SEED_PASSWORD.
Logs:   kubectl --context $Context -n $Ns logs -f deploy/agenticpilot
Remove: kubectl --context $Context delete namespace $Ns   (kind: kind delete cluster --name agenticpilot)
"@
