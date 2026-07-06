from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path

from app.models import RagAnswerResponse, RagQuestionRequest


def question_hash(question: str) -> str:
    return hashlib.sha256(question.strip().encode("utf-8")).hexdigest()


def audit_record(
    request: RagQuestionRequest,
    response: RagAnswerResponse,
    *,
    merchant_id: str | None,
    vector_backend: str,
    index_version: str,
    git_commit: str,
) -> dict:
    return {
        "event": "rag_answer",
        "created_at": datetime.now(UTC).isoformat(),
        "merchant_id": merchant_id or "",
        "correlation_id": request.correlation_id or "",
        "audience": request.audience,
        "question_sha256": question_hash(request.question),
        "question_chars": len(request.question.strip()),
        "max_citations": request.max_citations,
        "refusal_reason": response.refusal_reason or "",
        "confidence": response.confidence,
        "citation_paths": [citation.source_path for citation in response.citations],
        "retrieved_chunk_ids": [chunk.chunk_id for chunk in response.retrieved_chunks],
        "prompt_template_version": response.prompt_template_version,
        "answer_policy_version": response.answer_policy_version,
        "model_provider": response.model_provider,
        "model_name": response.model_name,
        "vector_backend": vector_backend,
        "index_version": index_version,
        "git_commit": git_commit,
    }


def append_audit_record(path: Path, record: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, sort_keys=True) + "\n")
