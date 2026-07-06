from __future__ import annotations

import argparse
import json
from pathlib import Path

from app.knowledge import KnowledgeBase, build_index, write_index
from app.vector_store import QdrantRetriever


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the MasonXPay docs-only RAG index.")
    parser.add_argument("--repo-root", default=".", help="Repository root containing approved documentation sources.")
    parser.add_argument("--index-path", default="data/rag_index.json", help="Output JSON index path.")
    parser.add_argument("--vector-backend", choices=["json", "qdrant"], default="json")
    parser.add_argument("--qdrant-url", default="http://localhost:6333")
    parser.add_argument("--qdrant-collection", default="masonxpay_docs")
    parser.add_argument("--check", action="store_true", help="Exit non-zero if the index file is missing or stale.")
    args = parser.parse_args()

    repo_root = Path(args.repo_root).resolve()
    index_path = Path(args.index_path).resolve()
    if args.check:
        expected = {"chunks": build_index(repo_root)}
        if not index_path.exists():
            raise SystemExit(f"RAG index missing: {index_path}")
        actual = json.loads(index_path.read_text(encoding="utf-8"))
        if normalized_index(actual) != normalized_index(expected):
            raise SystemExit(f"RAG index stale: regenerate {index_path}")
        print(f"RAG index is fresh: {index_path}")
        return

    count = write_index(repo_root, index_path)
    if args.vector_backend == "qdrant":
        base = KnowledgeBase(index_path, repo_root)
        retriever = QdrantRetriever(args.qdrant_url, args.qdrant_collection, base.chunks)
        retriever.ensure_index()
    print(f"Indexed {count} chunks into {args.index_path}")


def normalized_index(payload: dict) -> list[dict]:
    normalized: list[dict] = []
    for chunk in payload.get("chunks", []):
        item = dict(chunk)
        item.pop("last_indexed_at", None)
        normalized.append(item)
    return sorted(normalized, key=lambda value: value["chunk_id"])


if __name__ == "__main__":
    main()
