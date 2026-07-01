# Deploy guide here

Make sure it's quite easy for user to deploy to in modern infrastructure. Let's do some IaC tasks.

# Local

```shell
cd ../ docker compose up --build
```

# Kubernetes (minikube ≈ EKS template)

`k8s/` is a kustomize tree: a `base` (postgres + gateway-service + dashboard —
the same set the default `docker compose up` brings up), optional
`components` (kafka, redis, virtual-account, rail — one per compose profile),
and two overlays that consume the same base/components: `overlays/minikube`
(local dev) and `overlays/eks` (AWS target). Because both overlays share the
same base, the manifests you test locally on minikube are ~80-90% of what
runs on EKS — the overlay is where the two diverge (ingress class/annotations,
image registry, storage class, replica/resource sizing).

```
k8s/
  base/                  postgres, gateway-service, dashboard
  components/            kafka, redis, virtual-account, rail (optional, kustomize Components)
  overlays/minikube/      ingress-nginx, local docker images, dev secrets
  overlays/eks/           ALB ingress, ECR images, prod sizing — see overlays/eks/README.md
```

## Quick start (minikube)

```shell
./scripts/minikube-up.sh
# add the printed line to /etc/hosts, then open http://masonxpay.local
```

This starts/reuses a minikube profile, enables the ingress + metrics-server
addons, builds the gateway-service and dashboard images straight into
minikube's docker daemon, and applies `k8s/overlays/minikube`. By default it
brings up the lightweight stack (postgres + gateway-service + dashboard, with
the H7 payment simulator on so charges work without a real Stripe key). To
add Kafka/Redis/virtual-account/rail, uncomment the relevant lines under
`components:` in `k8s/overlays/minikube/kustomization.yaml` and the matching
`docker build` lines in `scripts/minikube-up.sh` before rerunning.

Tear down with `./scripts/minikube-down.sh` (asks before deleting the
minikube profile itself).

**If the ingress addon sits in `ImagePullBackOff`**: its controller image is
~300MB from `registry.k8s.io`, and on a flaky connection the pull can EOF
repeatedly before kubelet's backoff finally succeeds (`kubectl get pods -n
ingress-nginx -w` to watch it). This is a minikube/network issue, not this
repo's manifests. While waiting, reach the stack directly instead:
```shell
kubectl -n masonxpay port-forward svc/dashboard 3000:3000 &
kubectl -n masonxpay port-forward svc/gateway-service 8080:8080 &
```

## What this does and doesn't mimic about EKS

Matches: Deployments/Services/ConfigMaps/Secrets, HPA, resource
requests/limits, readiness/liveness probes, and — most importantly — the
kustomize base/overlay structure itself, so the same manifests really do run
on both.

Doesn't match (see `k8s/overlays/eks/README.md` for the full list): ALB vs
ingress-nginx, EBS/gp3 vs hostpath storage, IAM/IRSA, VPC networking, cluster
autoscaling, and — biggest gap — this template runs postgres/kafka/redis
in-cluster for convenience, where a real EKS deployment should use
RDS/MSK/ElastiCache instead.

# Cloud

## Cloudflare

Minimum usage, approaching to free plan.

## AWS

Cloudformation manage infrastructure, easy to use. But it's cloud-specific, maybe someday need migrate to Terraform.

```


ALB + WAF

Certificate Management

CloudFront CDN

VPC private network

	EC2 * 2 -> Backend

	Amplify -> Dashboard

	S3 -> SDK static files

	RDS PostgreSQL micro/small




```

## GCP

Not familiar with GCP yet. maybe we should use Terraform.