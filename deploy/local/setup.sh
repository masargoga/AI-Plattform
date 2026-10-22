#!/usr/bin/env bash
# Builds the image on this machine, makes sure a local Kubernetes cluster exists, and deploys AgenticPilot to it.
# Needs: docker, kubectl. With Docker Desktop turn on Settings > Kubernetes first; otherwise install kind and this script creates a cluster.
# Usage: AGENTICPILOT_SEED_PASSWORD='choose-one' [AGENTICPILOT_MODEL_API_KEY='sk-...'] deploy/local/setup.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${AGENTICPILOT_SEED_PASSWORD:?Set AGENTICPILOT_SEED_PASSWORD (the password for the users owner, admin and admin2)}"
IMAGE=agenticpilot:local
NS=agenticpilot

command -v docker >/dev/null || { echo "docker is not installed"; exit 1; }
command -v kubectl >/dev/null || { echo "kubectl is not installed (Docker Desktop installs it when Kubernetes is enabled)"; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker is not running. Start Docker Desktop first."; exit 1; }

echo "==> Building $IMAGE (the first build takes several minutes)"
docker build -t "$IMAGE" .

if kubectl config get-contexts -o name | grep -qx docker-desktop && kubectl --context docker-desktop get nodes >/dev/null 2>&1; then
  CONTEXT=docker-desktop
  echo "==> Using the Docker Desktop cluster"
  # With Docker Desktop's "kind" provisioner the nodes do not see local images: load it. With "kubeadm" this is not needed.
  if kubectl --context docker-desktop get nodes -o name | grep -q desktop-control-plane; then
    if command -v kind >/dev/null; then
      kind load docker-image "$IMAGE" --name desktop
    else
      docker save "$IMAGE" | docker exec -i desktop-control-plane ctr -n k8s.io images import -
    fi
  fi
else
  command -v kind >/dev/null || { echo "No Kubernetes cluster found. Enable Kubernetes in Docker Desktop (Settings > Kubernetes) or install kind: https://kind.sigs.k8s.io"; exit 1; }
  kind get clusters | grep -qx agenticpilot || kind create cluster --name agenticpilot
  kind load docker-image "$IMAGE" --name agenticpilot
  CONTEXT=kind-agenticpilot
fi
KUBECTL="kubectl --context $CONTEXT"

echo "==> Deploying to context $CONTEXT"
$KUBECTL create namespace "$NS" --dry-run=client -o yaml | $KUBECTL apply -f -
# Secrets are created from your shell environment; they are never written to a file in the repository.
$KUBECTL -n "$NS" create secret generic agenticpilot-secrets \
  --from-literal=AGENTICPILOT_SEED_PASSWORD="$AGENTICPILOT_SEED_PASSWORD" \
  --from-literal=AGENTICPILOT_MODEL_API_KEY="${AGENTICPILOT_MODEL_API_KEY:-}" \
  --dry-run=client -o yaml | $KUBECTL apply -f -
$KUBECTL apply -k deploy/k8s/overlays/local
# A changed image with the same tag needs a restart
$KUBECTL -n "$NS" rollout restart deploy/agenticpilot >/dev/null
if ! $KUBECTL -n "$NS" rollout status deploy/agenticpilot --timeout=300s; then
  echo; echo "==> The pod did not become ready. Details:"
  $KUBECTL -n "$NS" get pods -o wide
  $KUBECTL -n "$NS" describe pods -l app=agenticpilot
  echo; echo "==> Log of the application:"
  $KUBECTL -n "$NS" logs -l app=agenticpilot --tail=80 || true
  exit 1
fi

cat <<MSG

AgenticPilot is running. Open it with:

  kubectl --context $CONTEXT -n $NS port-forward svc/agenticpilot 8080:8080

then browse to http://localhost:8080 and sign in as admin, admin2 or owner with your AGENTICPILOT_SEED_PASSWORD.
Logs:   kubectl --context $CONTEXT -n $NS logs -f deploy/agenticpilot
Remove: kubectl --context $CONTEXT delete namespace $NS   (kind: kind delete cluster --name agenticpilot)
MSG
