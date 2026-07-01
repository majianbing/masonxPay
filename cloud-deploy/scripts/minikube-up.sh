#!/usr/bin/env bash
# Bring up the masonxpay stack on minikube as an EKS-like local template.
# See cloud-deploy/README.md for what this does and does not mimic about EKS.
set -euo pipefail

PROFILE="${MINIKUBE_PROFILE:-masonxpay}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OVERLAY_DIR="$REPO_ROOT/cloud-deploy/k8s/overlays/minikube"

echo "==> Starting minikube profile '$PROFILE' (docker driver)"
minikube start -p "$PROFILE" --driver=docker --cpus=4 --memory=6g

echo "==> Enabling ingress + metrics-server addons"
minikube addons enable ingress -p "$PROFILE"
minikube addons enable metrics-server -p "$PROFILE"

if [[ ! -f "$OVERLAY_DIR/secret.env" ]]; then
  echo "==> No secret.env found — copying secret.env.example (dev-only defaults)"
  cp "$OVERLAY_DIR/secret.env.example" "$OVERLAY_DIR/secret.env"
fi

echo "==> Pointing this shell's docker CLI at minikube's docker daemon"
eval "$(minikube -p "$PROFILE" docker-env)"

echo "==> Building images into minikube's docker daemon"
docker build -t masonxpay/gateway-service:local -f "$REPO_ROOT/backend/Dockerfile" "$REPO_ROOT/backend"
docker build -t masonxpay/dashboard:local -f "$REPO_ROOT/dashboard/Dockerfile" \
  --build-arg NEXT_PUBLIC_API_URL=http://masonxpay.local \
  --build-arg NEXT_PUBLIC_ENABLE_SIMULATOR_PROVIDER=true \
  "$REPO_ROOT"

# Only needed when the kafka/virtual-account/rail components are enabled in
# overlays/minikube/kustomization.yaml — uncomment to build them too.
# docker build -t masonxpay/kafka:local "$REPO_ROOT/monitor/kafka"
# docker build -t masonxpay/virtual-account:local -f "$REPO_ROOT/backend/Dockerfile.va" "$REPO_ROOT/backend"
# docker build -t masonxpay/rail-service:local -f "$REPO_ROOT/backend/Dockerfile.rail" "$REPO_ROOT/backend"
# docker build -t masonxpay/rail-simulator:local -f "$REPO_ROOT/backend/Dockerfile.railsim" "$REPO_ROOT/backend"

echo "==> Applying kustomize overlay"
kubectl apply -k "$OVERLAY_DIR"

echo "==> Waiting for rollout"
kubectl -n masonxpay rollout status statefulset/postgres --timeout=180s
kubectl -n masonxpay rollout status deployment/gateway-service --timeout=300s
kubectl -n masonxpay rollout status deployment/dashboard --timeout=180s

MINIKUBE_IP="$(minikube -p "$PROFILE" ip)"
echo ""
echo "==> Stack is up. Add this to /etc/hosts to reach it by name:"
echo "    $MINIKUBE_IP  masonxpay.local"
echo "==> Then open: http://masonxpay.local"
