# MasonXPay AI Service

Top-level Python coprocessor for advisory AI features. The first implemented surface is a docs-only RAG support assistant.

## Local Commands

Use Python 3.12 or 3.13 locally; Docker uses Python 3.12.

```bash
cd ai-service
python3.13 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m app.ingest --repo-root ..
.venv/bin/python -m app.ingest --repo-root .. --check
.venv/bin/python -m app.evaluate --repo-root .. --index-path /tmp/masonxpay-rag-eval-index.json --report-path /tmp/masonxpay-rag-eval-report.json
.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8090
.venv/bin/python -m unittest
```

The initial implementation is model-free: it indexes approved Markdown sources into `data/rag_index.json` and answers with cited source snippets. It refuses sensitive-data and unsupported questions instead of guessing.

Docker runs the same docs-only retriever against Qdrant:

```bash
AI_ASSISTANT_ENABLED=true \
RAG_REQUIRE_AUTH=true \
AI_SERVICE_AUTH_TOKEN="$(openssl rand -base64 32)" \
docker compose --profile ai up --build
```

The embeddings are deterministic local hashing vectors. This gives the service a real vector-store boundary without sending docs or questions to an external model provider.

Index metadata is exposed at:

```bash
curl http://localhost:8090/v1/rag/status
```

Use `python -m app.ingest --repo-root .. --check` as the manual freshness check. It ignores timestamp-only differences and fails when chunk identity, source hashes, git commit metadata, or content changes require re-indexing.

Run `python -m app.evaluate --repo-root .. --index-path /tmp/masonxpay-rag-eval-index.json --report-path /tmp/masonxpay-rag-eval-report.json` before changing retrieval behavior. The golden-question set checks required citations, sensitive-data refusals, and audience filtering without calling an external model provider. The report records index, prompt-template, answer-policy, model-provider, and model-name versions for framework bakeoffs and regression comparison.

The answer endpoint appends safe audit records to `RAG_AUDIT_LOG_PATH` (`data/rag_audit.jsonl` by default). Audit records contain correlation IDs, audience, hashed question text, citations, refusal/confidence, retrieved chunk IDs, index metadata, prompt/template versions, and local model metadata. They do not store raw questions, raw answers, secrets, payment records, provider payloads, or PII.

## Boundary

- Approved docs/help content only.
- No payment, ledger, provider payload, credential, card, webhook, or customer PII ingestion.
- Gateway remains the auth, tenant, mode, RBAC, rate-limit, and product policy gate.
- Set `RAG_REQUIRE_AUTH=true` and the same strong `AI_SERVICE_AUTH_TOKEN`/`RAG_AUTH_TOKEN` value for gateway-to-AI bearer authentication. Keep `ai-service` private behind `gateway-service`; startup logs warn when auth is not required.
