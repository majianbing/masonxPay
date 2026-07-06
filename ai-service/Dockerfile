FROM python:3.12-slim

WORKDIR /app

COPY ai-service/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY ai-service /app
COPY README.md /repo/README.md
COPY docs /repo/docs
COPY sdk/server/README.md /repo/sdk/server/README.md
COPY sdk/browser/README.md /repo/sdk/browser/README.md
COPY dashboard/README.md /repo/dashboard/README.md

ENV RAG_REPO_ROOT=/repo
ENV RAG_INDEX_PATH=/app/data/rag_index.json
ENV RAG_VECTOR_BACKEND=qdrant
ENV QDRANT_URL=http://qdrant:6333
ENV QDRANT_COLLECTION=masonxpay_docs

RUN python -m app.ingest --repo-root /repo --index-path /app/data/rag_index.json

EXPOSE 8090

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
