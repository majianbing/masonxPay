from __future__ import annotations

import os
import logging
import secrets
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, status

from app.audit import append_audit_record, audit_record
from app.knowledge import KnowledgeBase
from app.models import RagAnswerResponse, RagIndexStatus, RagQuestionRequest
from app.vector_store import QdrantRetriever

app = FastAPI(title="MasonXPay AI Service", version="0.1.0")
logger = logging.getLogger("masonxpay.ai_service")

INDEX_PATH = Path(os.getenv("RAG_INDEX_PATH", "data/rag_index.json"))
REPO_ROOT = Path(os.getenv("RAG_REPO_ROOT", "."))
VECTOR_BACKEND = os.getenv("RAG_VECTOR_BACKEND", "json")
QDRANT_URL = os.getenv("QDRANT_URL", "http://localhost:6333")
QDRANT_COLLECTION = os.getenv("QDRANT_COLLECTION", "masonxpay_docs")
AUDIT_LOG_PATH = Path(os.getenv("RAG_AUDIT_LOG_PATH", "data/rag_audit.jsonl"))
RAG_REQUIRE_AUTH = os.getenv("RAG_REQUIRE_AUTH", "false").lower() == "true"
RAG_AUTH_TOKEN = os.getenv("RAG_AUTH_TOKEN", "")


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


@app.on_event("startup")
def warn_if_bootstrap_security_is_active() -> None:
    if not RAG_REQUIRE_AUTH:
        logger.warning(
            "RAG7 TODO: ai-service RAG endpoints are running without service authentication. "
            "Keep this service private behind gateway-service until RAG_REQUIRE_AUTH is enabled."
        )
    elif not RAG_AUTH_TOKEN:
        logger.error("RAG7 configuration error: RAG_REQUIRE_AUTH=true but RAG_AUTH_TOKEN is empty.")
    if VECTOR_BACKEND == "qdrant" and QDRANT_URL.startswith("http://"):
        logger.warning(
            "RAG7 TODO: Qdrant is configured over plain HTTP. Use private networking and "
            "auth/TLS or equivalent network controls before production exposure."
        )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "vectorBackend": VECTOR_BACKEND}


def require_rag_auth(authorization: str | None = Header(default=None)) -> None:
    if not RAG_REQUIRE_AUTH:
        return
    if not RAG_AUTH_TOKEN:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="RAG auth is not configured")
    prefix = "Bearer "
    if authorization is None or not authorization.startswith(prefix):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing RAG bearer token")
    token = authorization[len(prefix):]
    if not secrets.compare_digest(token, RAG_AUTH_TOKEN):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid RAG bearer token")


@app.post("/v1/rag/answer", response_model=RagAnswerResponse, dependencies=[Depends(require_rag_auth)])
def answer(request: RagQuestionRequest) -> RagAnswerResponse:
    response = knowledge_base.answer(request)
    status = knowledge_base.status(VECTOR_BACKEND)
    append_audit_record(
        AUDIT_LOG_PATH,
        audit_record(
            request,
            response,
            merchant_id=request.merchant_id,
            vector_backend=VECTOR_BACKEND,
            index_version=status.index_version,
            git_commit=status.git_commit,
        ),
    )
    return response


@app.get("/v1/rag/status", response_model=RagIndexStatus, dependencies=[Depends(require_rag_auth)])
def rag_status() -> RagIndexStatus:
    return knowledge_base.status(VECTOR_BACKEND)
