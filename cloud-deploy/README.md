# Deploy guide here

Make sure it's quite easy for user to deploy to in modern infrastructure. Let's do some IaC tasks.

# Local

```shell
cd ../ docker compose up --build
```

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