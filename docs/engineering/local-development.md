# Local Development

Use Docker Compose as the default local path. It avoids requiring local Java, Node, PostgreSQL, Redis, and Kafka setup.

## Docker

```bash
cp .env.docker.example .env
docker compose up --build
```

First boot can take several minutes because Maven and Next.js dependencies are downloaded inside Docker.

Useful commands:

```bash
docker compose logs -f
docker compose logs -f backend
docker compose down
docker compose down -v
docker compose up --build
docker compose --profile infra up --build
```

## Preview Stack

Use the preview stack when validating high-throughput behavior with Kafka workers and Redis hot path enabled.

```bash
docker compose -p masonxpay-preview --env-file .env.preview -f docker-compose.yml -f docker-compose.preview.yml up --build
```

## Manual Development

```bash
cd backend && mvn spring-boot:run
cd dashboard && npm run dev
```

Manual backend defaults to the local Spring profile and runs on the configured backend port. Dashboard runs on port `3000`.

## Build Commands

```bash
cd backend && mvn compile
cd backend && mvn test
cd dashboard && npm run build
cd sdk/server && npm run build
cd sdk/browser && npm run build && npm run bundle
```

## Benchmarks

```bash
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.bench.yml --profile bench run --rm k6
```

Benchmark outputs are written to `bench/results/`.
