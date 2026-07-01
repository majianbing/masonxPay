# EKS overlay

This overlay is the same base + components as `overlays/minikube`, retargeted at
a real EKS cluster. It's a starting template, not a finished production
manifest — treat every `<PLACEHOLDER>` as a required edit.

## Prerequisites on the cluster

- **AWS Load Balancer Controller** installed (Helm chart
  `eks/aws-load-balancer-controller`), with an IRSA-bound service account —
  the ALB annotations in `ingress.yaml` do nothing without it.
- **EBS CSI driver** installed and a `gp3` StorageClass present if you want
  EBS-backed PVCs (see "Storage" below).
- **ACM certificate** issued for your domain, referenced in
  `ingress.yaml`'s `certificate-arn` annotation.
- **ALB health check path**: `ingress.yaml` points the ALB target group at
  `/actuator/health/liveness`. `SecurityConfig.java` currently `permitAll`s
  the exact path `/actuator/health` only, so `/actuator/health/liveness`
  returns 401 as-is — add it (and `/actuator/health/readiness`) to the
  permitted list before using this ingress for real. Do not point the ALB
  check at plain `/actuator/health` instead: its aggregate status goes DOWN
  whenever a tenant has zero active payment connectors configured, which is
  normal on a fresh deploy and would flap ALB target health for a reason
  that has nothing to do with the pod actually being healthy.
- Images pushed to **ECR** at the repo paths referenced in
  `kustomization.yaml`'s `images:` block.

## What carries over ~80-90% from minikube

Deployments, Services, ConfigMaps, Secrets, HPA, resource requests/limits,
readiness/liveness probes, and the kustomize base/components/overlay
structure itself all behave the same way on EKS as on minikube — that's the
point of using kustomize here instead of hand-writing two separate manifest
trees.

## What does NOT carry over — real gaps vs EKS

- **Ingress → ALB vs nginx**: minikube uses the ingress-nginx addon; this
  overlay assumes the AWS Load Balancer Controller provisions a real ALB.
  Annotations differ completely (see `ingress.yaml`).
- **StorageClass**: minikube's `standard` class is hostpath on the single
  node. EKS needs the EBS CSI driver and typically a `gp3` StorageClass.
  Base leaves `storageClassName` unset (cluster default) — set one
  explicitly here if your cluster's default isn't what you want, or skip
  in-cluster Postgres entirely (see below).
- **IAM**: nothing here uses IRSA (IAM Roles for Service Accounts). Any pod
  that needs to call AWS APIs (S3, Secrets Manager, SES, etc.) needs a
  ServiceAccount annotated with `eks.amazonaws.com/role-arn` — not modeled.
- **Secrets**: `secretGenerator` from a local `secret.env` file is fine for a
  first smoke test but isn't how production EKS should manage secrets — use
  External Secrets Operator syncing from AWS Secrets Manager instead, and
  drop the secretGenerator for a `Secret` of type unset created by the
  operator.
- **Networking**: no VPC CNI security-group-per-pod modeling, no Network
  Policies, no private-subnet placement — this is plain namespace isolation.
- **Node/cluster autoscaling**: no Cluster Autoscaler / Karpenter
  NodePool config. The HPA here only scales pod replicas, not nodes.
- **Multi-AZ control plane**: EKS's managed control plane is inherently
  multi-AZ; minikube's is a single node. Not something a manifest models —
  just noting the gap.

## Managed-service swap-out (recommended before real traffic)

The `kafka`, `redis`, and `postgres` (in base) components run these as
single-replica in-cluster Deployments/StatefulSets with no HA, backups, or
managed patching — fine for a demo, not for production. For an EKS
deployment, prefer:

| In-cluster (this repo)     | AWS managed equivalent |
|-----------------------------|------------------------|
| `base/postgres` (StatefulSet) | RDS PostgreSQL (Multi-AZ) |
| `components/kafka`          | MSK (Managed Streaming for Kafka) |
| `components/redis`          | ElastiCache for Redis |

Swapping to managed services means: delete the corresponding
component/base resource from this overlay, and point
`DB_HOST`/`KAFKA_BOOTSTRAP_SERVERS`/`REDIS_HOST` in
`base/gateway-service/configmap.yaml` (patch it here, don't edit base) at
the managed endpoint instead.
