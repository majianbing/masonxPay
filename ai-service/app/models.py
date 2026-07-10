from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


def to_camel(value: str) -> str:
    parts = value.split("_")
    return parts[0] + "".join(part.capitalize() for part in parts[1:])


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


Audience = Literal["merchant", "developer", "platform-admin", "operator"]
SourceType = Literal["architecture", "engineering", "planning", "sdk", "dashboard", "readme"]
Stability = Literal["stable", "active-plan", "historical"]


class Citation(ApiModel):
    source_path: str
    heading_path: str
    source_type: SourceType
    stability: Stability


class RetrievedChunk(ApiModel):
    chunk_id: str
    text: str
    score: float
    citation: Citation


class RagQuestionRequest(ApiModel):
    question: str = Field(min_length=1, max_length=2_000)
    audience: Audience = "merchant"
    max_citations: int = Field(default=4, ge=1, le=8)
    correlation_id: str | None = Field(default=None, max_length=128)
    merchant_id: str | None = Field(default=None, max_length=128)


class RagAnswerResponse(ApiModel):
    answer: str
    citations: list[Citation]
    refusal_reason: str | None = None
    confidence: Literal["none", "low", "medium", "high"]
    retrieved_chunks: list[RetrievedChunk] = Field(default_factory=list)
    prompt_template_version: str
    answer_policy_version: str
    model_provider: str
    model_name: str


class SourceSummary(ApiModel):
    source_path: str
    chunk_count: int
    source_sha256: str
    source_type: SourceType
    stability: Stability


class RagIndexStatus(ApiModel):
    index_version: str
    git_commit: str
    last_indexed_at: str
    chunk_count: int
    source_count: int
    vector_backend: str
    prompt_template_version: str
    answer_policy_version: str
    model_provider: str
    model_name: str
    sources: list[SourceSummary]
