from __future__ import annotations

import hashlib
import json
import re
import subprocess
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Iterable

from app.models import Citation, RagAnswerResponse, RagIndexStatus, RagQuestionRequest, RetrievedChunk, SourceSummary
from app.text import tokenize
from app.vector_store import JsonRetriever, Retriever

INDEX_VERSION = "rag-docs-v1"
HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
SENSITIVE_RE = re.compile(
    r"\b("
    r"api[_ -]?keys?|secrets?|passwords?|tokens?|credentials?|private[_ -]?keys?|webhook[_ -]?signatures?|"
    r"pan|card number|cvv|cvc|track data|raw payload|database row|customer pii|ssn"
    r")\b",
    re.IGNORECASE,
)

ALLOWED_SOURCES = [
    "README.md",
    "docs/architecture",
    "docs/engineering",
    "docs/planning",
    "sdk/server/README.md",
    "sdk/browser/README.md",
    "dashboard/README.md",
]

AUDIENCE_BY_SOURCE_TYPE = {
    "architecture": {"merchant", "developer", "platform-admin", "operator"},
    "engineering": {"developer", "platform-admin", "operator"},
    "planning": {"developer", "platform-admin", "operator"},
    "sdk": {"merchant", "developer", "platform-admin", "operator"},
    "dashboard": {"merchant", "developer", "platform-admin", "operator"},
    "readme": {"merchant", "developer", "platform-admin", "operator"},
}


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    text: str
    source_path: str
    source_type: str
    stability: str
    module: str
    audience: set[str]
    heading_path: str
    chunk_index: int
    source_sha256: str


def source_metadata(path: str) -> tuple[str, str, str]:
    if path == "README.md":
        return "readme", "stable", "root"
    if path.startswith("docs/architecture/"):
        return "architecture", "stable", module_from_path(path)
    if path.startswith("docs/engineering/"):
        return "engineering", "stable", module_from_path(path)
    if path.startswith("docs/planning/"):
        return "planning", "active-plan", module_from_path(path)
    if path.startswith("sdk/server/"):
        return "sdk", "stable", "server-sdk"
    if path.startswith("sdk/browser/"):
        return "sdk", "stable", "browser-sdk"
    if path.startswith("dashboard/"):
        return "dashboard", "stable", "dashboard"
    return "readme", "stable", "root"


def module_from_path(path: str) -> str:
    lower = path.lower()
    for name in ("gateway", "dashboard", "rail-simulator", "rail", "virtual-account", "server-sdk", "browser-sdk"):
        if name in lower:
            return name
    return "general"


def iter_allowed_markdown(repo_root: Path) -> Iterable[Path]:
    for rel in ALLOWED_SOURCES:
        candidate = repo_root / rel
        if candidate.is_file() and candidate.suffix.lower() == ".md":
            yield candidate
        elif candidate.is_dir():
            yield from sorted(candidate.rglob("*.md"))


def stable_chunk_id(source_path: str, heading_path: str, chunk_index: int) -> str:
    basis = f"{source_path}\n{heading_path}\n{chunk_index}"
    digest = hashlib.sha256(basis.encode("utf-8")).hexdigest()[:16]
    return f"{source_path}#{chunk_index}-{digest}"


def stable_point_id(chunk_id: str) -> str:
    import uuid

    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"masonxpay-rag:{chunk_id}"))


def source_sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def current_git_commit(repo_root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=repo_root,
            check=True,
            capture_output=True,
            text=True,
            timeout=2,
        )
        return result.stdout.strip()
    except Exception:
        return "unknown"


def chunk_markdown(repo_root: Path, path: Path, max_chars: int = 1_600) -> list[Chunk]:
    rel_path = path.relative_to(repo_root).as_posix()
    source_type, stability, module = source_metadata(rel_path)
    audience = AUDIENCE_BY_SOURCE_TYPE[source_type]
    source_text = path.read_text(encoding="utf-8")
    source_hash = source_sha256(source_text)
    chunks: list[Chunk] = []
    heading_stack: list[tuple[int, str]] = []
    buffer: list[str] = []

    def flush() -> None:
        text = "\n".join(buffer).strip()
        if not text:
            return
        heading_path = " > ".join(title for _, title in heading_stack) or "Document"
        chunk_index = len(chunks) + 1
        chunk_id = stable_chunk_id(rel_path, heading_path, chunk_index)
        chunks.append(
            Chunk(
                chunk_id,
                text,
                rel_path,
                source_type,
                stability,
                module,
                set(audience),
                heading_path,
                chunk_index,
                source_hash,
            )
        )
        buffer.clear()

    for raw_line in source_text.splitlines():
        heading = HEADING_RE.match(raw_line)
        if heading:
            flush()
            level = len(heading.group(1))
            title = heading.group(2).strip()
            heading_stack = [(lvl, value) for lvl, value in heading_stack if lvl < level]
            heading_stack.append((level, title))
            continue
        if len("\n".join(buffer)) + len(raw_line) > max_chars:
            flush()
        buffer.append(raw_line)
    flush()
    return chunks


def build_index(repo_root: Path) -> list[dict]:
    chunks: list[dict] = []
    git_commit = current_git_commit(repo_root)
    last_indexed_at = datetime.now(UTC).isoformat()
    for path in iter_allowed_markdown(repo_root):
        for chunk in chunk_markdown(repo_root, path):
            chunks.append(
                {
                    "chunk_id": chunk.chunk_id,
                    "point_id": stable_point_id(chunk.chunk_id),
                    "text": chunk.text,
                    "source_path": chunk.source_path,
                    "source_type": chunk.source_type,
                    "stability": chunk.stability,
                    "module": chunk.module,
                    "audience": sorted(chunk.audience),
                    "heading_path": chunk.heading_path,
                    "chunk_index": chunk.chunk_index,
                    "source_sha256": chunk.source_sha256,
                    "index_version": INDEX_VERSION,
                    "last_indexed_at": last_indexed_at,
                    "git_commit": git_commit,
                    "tokens": tokenize(chunk.text),
                }
            )
    return chunks


def write_index(repo_root: Path, index_path: Path) -> int:
    chunks = build_index(repo_root)
    index_path.parent.mkdir(parents=True, exist_ok=True)
    index_path.write_text(json.dumps({"chunks": chunks}, indent=2), encoding="utf-8")
    return len(chunks)


class KnowledgeBase:
    def __init__(self, index_path: Path, repo_root: Path | None = None, retriever: Retriever | None = None):
        self.index_path = index_path
        self.repo_root = repo_root
        self.retriever = retriever or JsonRetriever(self.chunks)
        self._chunks: list[dict] | None = None

    def chunks(self) -> list[dict]:
        if self._chunks is None:
            if not self.index_path.exists():
                if self.repo_root is None:
                    raise FileNotFoundError(f"RAG index not found: {self.index_path}")
                write_index(self.repo_root, self.index_path)
            payload = json.loads(self.index_path.read_text(encoding="utf-8"))
            self._chunks = payload.get("chunks", [])
        return self._chunks

    def answer(self, request: RagQuestionRequest) -> RagAnswerResponse:
        question = request.question.strip()
        if SENSITIVE_RE.search(question):
            return RagAnswerResponse(
                answer="I cannot help with secrets, raw payment data, card data, provider payloads, or unredacted PII. Ask a documentation or setup question that does not require sensitive data.",
                citations=[],
                refusal_reason="sensitive_data",
                confidence="none",
            )

        matches = self.retrieve(question, request.audience, request.max_citations)
        if not matches:
            return RagAnswerResponse(
                answer="I do not have enough approved MasonXPay documentation to answer that. Try asking about setup, SDKs, connectors, routing, subscriptions, rails, ledger concepts, or AI safety boundaries.",
                citations=[],
                refusal_reason="insufficient_evidence",
                confidence="none",
            )

        citations = [citation_from_chunk(chunk) for chunk, _ in matches]
        chunks = [
            RetrievedChunk(chunk_id=chunk["chunk_id"], text=chunk["text"], score=score, citation=citation_from_chunk(chunk))
            for chunk, score in matches
        ]
        answer = synthesize_answer(question, [chunk for chunk, _ in matches])
        confidence = "high" if matches[0][1] >= 0.45 and len(matches) >= 2 else "medium" if matches[0][1] >= 0.25 else "low"
        return RagAnswerResponse(answer=answer, citations=citations, confidence=confidence, retrieved_chunks=chunks)

    def retrieve(self, question: str, audience: str, limit: int) -> list[tuple[dict, float]]:
        return self.retriever.retrieve(question, audience, limit)

    def status(self, vector_backend: str) -> RagIndexStatus:
        chunks = self.chunks()
        if not chunks:
            return RagIndexStatus(
                index_version=INDEX_VERSION,
                git_commit="unknown",
                last_indexed_at="unknown",
                chunk_count=0,
                source_count=0,
                vector_backend=vector_backend,
                sources=[],
            )
        sources: dict[str, SourceSummary] = {}
        for chunk in chunks:
            source_path = chunk["source_path"]
            existing = sources.get(source_path)
            if existing is None:
                sources[source_path] = SourceSummary(
                    source_path=source_path,
                    chunk_count=1,
                    source_sha256=chunk["source_sha256"],
                    source_type=chunk["source_type"],
                    stability=chunk["stability"],
                )
            else:
                sources[source_path] = SourceSummary(
                    source_path=existing.source_path,
                    chunk_count=existing.chunk_count + 1,
                    source_sha256=existing.source_sha256,
                    source_type=existing.source_type,
                    stability=existing.stability,
                )
        return RagIndexStatus(
            index_version=chunks[0].get("index_version", INDEX_VERSION),
            git_commit=chunks[0].get("git_commit", "unknown"),
            last_indexed_at=chunks[0].get("last_indexed_at", "unknown"),
            chunk_count=len(chunks),
            source_count=len(sources),
            vector_backend=vector_backend,
            sources=sorted(sources.values(), key=lambda source: source.source_path),
        )


def citation_from_chunk(chunk: dict) -> Citation:
    return Citation(
        source_path=chunk["source_path"],
        heading_path=chunk["heading_path"],
        source_type=chunk["source_type"],
        stability=chunk["stability"],
    )


def synthesize_answer(question: str, chunks: list[dict]) -> str:
    query = set(tokenize(question))
    snippets: list[str] = []
    for chunk in chunks:
        sentences = re.split(r"(?<=[.!?])\s+", re.sub(r"\s+", " ", chunk["text"]).strip())
        best = sorted(sentences, key=lambda s: len(query & set(tokenize(s))), reverse=True)
        for sentence in best:
            if len(query & set(tokenize(sentence))) > 0 and sentence not in snippets:
                snippets.append(sentence)
                break
    if not snippets:
        snippets = [re.sub(r"\s+", " ", chunks[0]["text"]).strip()[:500]]
    return " ".join(snippets[:4])
