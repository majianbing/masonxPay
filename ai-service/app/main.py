from __future__ import annotations

import os
from pathlib import Path

from fastapi import FastAPI

from app.knowledge import KnowledgeBase
from app.models import RagAnswerResponse, RagIndexStatus, RagQuestionRequest
from app.vector_store import QdrantRetriever

app = FastAPI(title="MasonXPay AI Service", version="0.1.0")

INDEX_PATH = Path(os.getenv("RAG_INDEX_PATH", "data/rag_index.json"))
REPO_ROOT = Path(os.getenv("RAG_REPO_ROOT", "."))
VECTOR_BACKEND = os.getenv("RAG_VECTOR_BACKEND", "json")
QDRANT_URL = os.getenv("QDRANT_URL", "http://localhost:6333")
QDRANT_COLLECTION = os.getenv("QDRANT_COLLECTION", "masonxpay_docs")


def create_knowledge_base() -> KnowledgeBase:
    base = KnowledgeBase(INDEX_PATH, REPO_ROOT)
    if VECTOR_BACKEND == "qdrant":
        return KnowledgeBase(
            INDEX_PATH,
            REPO_ROOT,
            QdrantRetriever(QDRANT_URL, QDRANT_COLLECTION, base.chunks),
        )
    return base


knowledge_base = create_knowledge_base()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "vectorBackend": VECTOR_BACKEND}


@app.post("/v1/rag/answer", response_model=RagAnswerResponse)
def answer(request: RagQuestionRequest) -> RagAnswerResponse:
    return knowledge_base.answer(request)


@app.get("/v1/rag/status", response_model=RagIndexStatus)
def rag_status() -> RagIndexStatus:
    return knowledge_base.status(VECTOR_BACKEND)
