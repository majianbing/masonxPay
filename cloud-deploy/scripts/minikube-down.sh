#!/usr/bin/env bash
# Tear down the masonxpay stack. Run manually — not called by minikube-up.sh.
set -euo pipefail

PROFILE="${MINIKUBE_PROFILE:-masonxpay}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "==> Deleting masonxpay namespace (workloads + PVCs)"
kubectl delete namespace masonxpay --ignore-not-found

read -r -p "Also stop/delete the minikube profile '$PROFILE'? [y/N] " answer
if [[ "$answer" =~ ^[Yy]$ ]]; then
  minikube delete -p "$PROFILE"
fi
