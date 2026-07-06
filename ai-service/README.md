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
.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8090
.venv/bin/python -m unittest
```

The initial implementation is model-free: it indexes approved Markdown sources into `data/rag_index.json` and answers with cited source snippets. It refuses sensitive-data and unsupported questions instead of guessing.

Docker runs the same docs-only retriever against Qdrant:

```bash
AI_ASSISTANT_ENABLED=true docker compose --profile ai up --build
```

The embeddings are deterministic local hashing vectors. This gives the service a real vector-store boundary without sending docs or questions to an external model provider.

Index metadata is exposed at:

```bash
curl http://localhost:8090/v1/rag/status
```

Use `python -m app.ingest --repo-root .. --check` as the manual freshness check. It ignores timestamp-only differences and fails when chunk identity, source hashes, git commit metadata, or content changes require re-indexing.

## Boundary

- Approved docs/help content only.
- No payment, ledger, provider payload, credential, card, webhook, or customer PII ingestion.
- Gateway remains the auth, tenant, mode, RBAC, rate-limit, and product policy gate.
