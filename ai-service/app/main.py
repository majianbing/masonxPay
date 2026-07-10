from __future__ import annotations

import os
import logging
import secrets
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, status

from app.audit import append_audit_record, audit_record
from app.knowledge import KnowledgeBase
from app.llm import LlmError, create_llm_client
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
# Answer generation. Default `local` keeps the no-external-AI baseline; set
# RAG_MODEL_PROVIDER=openai + OPENAI_API_KEY to generate answers with a frontier model.
RAG_MODEL_PROVIDER = os.getenv("RAG_MODEL_PROVIDER", "local")
RAG_MODEL_NAME = os.getenv("RAG_MODEL_NAME", "gpt-4o-mini")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
OPENAI_BASE_URL = os.getenv("OPENAI_BASE_URL") or None


def build_llm_client():
    try:
        return create_llm_client(RAG_MODEL_PROVIDER, RAG_MODEL_NAME, OPENAI_API_KEY, base_url=OPENAI_BASE_URL)
    except LlmError as exc:
        # Misconfiguration should degrade to local, not crash the service.
        logger.error("Model provider misconfigured (%s); using local extractive answers.", exc)
        return None


def create_knowledge_base() -> KnowledgeBase:
    llm_client = build_llm_client()
    retriever = (
        QdrantRetriever(QDRANT_URL, QDRANT_COLLECTION, KnowledgeBase(INDEX_PATH, REPO_ROOT).chunks)
        if VECTOR_BACKEND == "qdrant"
        else None
    )
    return KnowledgeBase(INDEX_PATH, REPO_ROOT, retriever, llm_client=llm_client)


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
    if RAG_MODEL_PROVIDER.lower() != "local":
        logger.warning(
            "External AI generation is ACTIVE (provider=%s, model=%s). Only approved "
            "retrieved documentation and the user question are sent to the provider; keep "
            "secrets, PII, and payment data out of the index.",
            RAG_MODEL_PROVIDER,
            RAG_MODEL_NAME,
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
