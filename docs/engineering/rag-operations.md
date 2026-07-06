# RAG Assistant Operations

This guide covers the bootstrap RAG support assistant runtime. It is docs-only and advisory: the gateway remains the policy gate, and the AI service must not own payment, connector, routing, ledger, approval, tenant, or credential authority.

## Local Runtime

Run the AI profile only when testing the assistant:

```bash
AI_ASSISTANT_ENABLED=true \
RAG_REQUIRE_AUTH=true \
AI_SERVICE_AUTH_TOKEN="$(openssl rand -base64 32)" \
docker compose --profile ai up --build
```

The profile starts:

- `ai-service` on port `8090`
- `qdrant` on port `6333`
- persistent `qdrant_data` Docker volume

Use a strong `AI_SERVICE_AUTH_TOKEN` whenever `AI_ASSISTANT_ENABLED=true`. Gateway sends it as a bearer token to `ai-service`; `ai-service` requires it when `RAG_REQUIRE_AUTH=true`. Local bootstrap runs may leave `RAG_REQUIRE_AUTH=false`, but startup logs warn because that mode must not be exposed outside a private developer network.

Useful direct checks:

```bash
curl http://localhost:8090/health
curl http://localhost:8090/v1/rag/status
```

For protected dashboard flows, call the gateway instead of the AI service directly:

```text
GET /api/v1/merchants/{merchantId}/assistant/status
POST /api/v1/merchants/{merchantId}/assistant/questions
```

## Indexed Data Boundary

Allowed content:

- approved docs/help content
- chunk metadata
- deterministic local embeddings
- citations and source-version metadata
- optional future eval artifacts

Forbidden content:

- production logs
- raw database rows
- provider payloads
- webhook bodies or signature headers
- secrets, credentials, API keys, private keys, tokens
- PAN, CVV, track data, cardholder data
- customer PII or payment/ledger records

The vector database is not a shadow operational datastore. Do not index raw source code broadly, logs, exports, database dumps, or support tickets unless a later phase defines a narrow allowlisted generator and redaction policy.

## Qdrant Production Requirements

Local Docker exposes Qdrant without auth or TLS for developer convenience only. Production deployments must not expose Qdrant publicly.

Required controls:

- Put Qdrant on a private network reachable only from `ai-service`.
- Enable TLS for service-to-Qdrant traffic where the deployment platform supports it, or terminate TLS at a private service mesh/proxy.
- Enable API-key authentication or equivalent network-layer authorization.
- Store Qdrant API keys in the platform secret manager, not in repo files or dashboard settings.
- Restrict Qdrant dashboards/admin endpoints to operators only.
- Use separate collections or namespaces per environment. Do not share TEST/local indexes with production.
- Do not allow direct dashboard or browser access to Qdrant.

## Backup And Retention

The RAG index is reproducible from allowlisted docs, but backups still matter for fast restore and audit continuity.

Minimum bootstrap policy:

- Snapshot the Qdrant collection after each successful production indexing run.
- Keep at least the current and previous successful snapshots.
- Retain snapshots for 30 days unless the deployment has stricter internal retention.
- Record `index_version`, `git_commit`, `last_indexed_at`, chunk count, source count, and collection name with each snapshot.
- Test restore before marking a production indexing process complete.

Retention:

- Qdrant should contain only the current allowlisted documentation index plus service metadata.
- Removed chunks must be deleted during sync; stale vectors are not retained intentionally.
- Source documents remain governed by git history and docs retention, not by Qdrant.

## Freshness Workflow

Manual freshness check:

```bash
cd ai-service
.venv/bin/python -m app.ingest --repo-root .. --index-path data/rag_index.json --check
```

The check ignores timestamp-only differences and fails when stable chunk identity, source hashes, git commit metadata, or content differ. CI can use the same command once an index artifact is generated in the pipeline.

Recommended production indexing workflow:

1. Build an index from the exact application/documentation commit.
2. Run `--check` against the generated artifact.
3. Upsert to Qdrant with stable point IDs.
4. Delete stale points not present in the current index.
5. Query `/v1/rag/status` and verify expected `git_commit`, chunk count, source count, and backend.
6. Run the golden-question eval suite when RAG6 exists.
7. Snapshot Qdrant.
8. Promote the service version and index together.

## Runtime Smoke Test

After deployment, validate:

```bash
curl http://ai-service:8090/health
curl http://ai-service:8090/v1/rag/status
curl -H 'Content-Type: application/json' \
  -d '{"question":"How does TEST and LIVE mode isolation work?","audience":"merchant","maxCitations":3}' \
  http://ai-service:8090/v1/rag/answer
```

Expected:

- health reports `vectorBackend` as `qdrant`
- status reports the expected index version and source counts
- answer includes citations and no refusal for supported docs questions
- merchant audience does not retrieve developer/operator-only docs

## Known Bootstrap Gaps

- Embeddings are deterministic local hashing vectors, not semantic model embeddings.
- Golden-question evals are not yet automated.
- Dashboard build verification is still required in this branch because local `npm ci` previously hung.
