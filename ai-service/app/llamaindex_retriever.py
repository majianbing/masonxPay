"""LlamaIndex-backed retriever for the RAG5 framework bakeoff.

Implements the same `Retriever` contract as `JsonRetriever`/`QdrantRetriever`, so it
is a drop-in behind `KnowledgeBase` selected by `RAG_VECTOR_BACKEND=llamaindex`.

Design choices that keep the bakeoff fair and the governance owned:
- Reuses the project's local hash embeddings (`embedding_for_text`) via a custom
  LlamaIndex embedding, so any quality delta versus the raw retriever comes from the
  framework's retrieval machinery, not a different embedding model.
- Audience filtering is applied *in retrieval* via a metadata filter (before ranking),
  with a defensive post-check so a non-audience chunk can never reach the answer.
- The stability/source `ranking_multiplier` (a MasonXPay policy, not the framework's job)
  is applied here too, identically to the other retrievers.
"""

from __future__ import annotations

import threading

from app.vector_store import embedding_for_text, ranking_multiplier

try:  # pragma: no cover - exercised indirectly; import guarded for the local-only baseline
    from llama_index.core.embeddings import BaseEmbedding

    class LocalHashEmbedding(BaseEmbedding):
        """Wraps the project's deterministic hash embedding as a LlamaIndex embedding."""

        def _get_query_embedding(self, query: str) -> list[float]:
            return embedding_for_text(query)

        def _get_text_embedding(self, text: str) -> list[float]:
            return embedding_for_text(text)

        def _get_text_embeddings(self, texts: list[str]) -> list[list[float]]:
            return [embedding_for_text(text) for text in texts]

        async def _aget_query_embedding(self, query: str) -> list[float]:
            return embedding_for_text(query)

        async def _aget_text_embedding(self, text: str) -> list[float]:
            return embedding_for_text(text)

    _IMPORT_ERROR: Exception | None = None
except Exception as exc:  # llama-index-core not installed
    BaseEmbedding = None  # type: ignore[assignment]
    LocalHashEmbedding = None  # type: ignore[assignment]
    _IMPORT_ERROR = exc


class LlamaIndexRetriever:
    def __init__(self, chunks_provider):
        if _IMPORT_ERROR is not None:
            raise RuntimeError(
                "RAG_VECTOR_BACKEND=llamaindex requires llama-index-core"
            ) from _IMPORT_ERROR
        self.chunks_provider = chunks_provider
        self._index = None
        self._audiences: set[str] = set()
        self._lock = threading.Lock()

    def _ensure_index(self):
        if self._index is not None:
            return self._index
        with self._lock:
            if self._index is None:
                from llama_index.core import VectorStoreIndex
                from llama_index.core.schema import TextNode

                nodes = []
                audiences: set[str] = set()
                for chunk in self.chunks_provider():
                    metadata = {key: value for key, value in chunk.items() if key != "text"}
                    node = TextNode(text=chunk["text"], id_=chunk["chunk_id"], metadata=metadata)
                    node.embedding = embedding_for_text(chunk["text"])
                    # Metadata is stored for filtering/citations only, never embedded.
                    node.excluded_embed_metadata_keys = list(metadata.keys())
                    node.excluded_llm_metadata_keys = list(metadata.keys())
                    nodes.append(node)
                    audiences.update(chunk.get("audience", []))
                self._audiences = audiences
                self._index = VectorStoreIndex(nodes, embed_model=LocalHashEmbedding())
            return self._index

    def retrieve(self, question: str, audience: str, limit: int) -> list[tuple[dict, float]]:
        from llama_index.core.vector_stores.types import FilterOperator, MetadataFilter, MetadataFilters

        index = self._ensure_index()
        # SimpleVectorStore asserts when a metadata filter matches zero candidates, so
        # short-circuit when no indexed chunk is visible to this audience.
        if audience not in self._audiences:
            return []
        # Over-fetch candidates, then re-rank with the stability/source boost so a stable
        # chunk just outside `limit` can still win (parity with the Qdrant retriever).
        candidate_limit = max(limit * 5, 20)
        filters = MetadataFilters(
            filters=[MetadataFilter(key="audience", value=audience, operator=FilterOperator.CONTAINS)]
        )
        retriever = index.as_retriever(similarity_top_k=candidate_limit, filters=filters)

        scored: list[tuple[dict, float]] = []
        for item in retriever.retrieve(question):
            chunk = dict(item.node.metadata)
            if audience not in set(chunk.get("audience", [])):  # defensive governance guard
                continue
            chunk["text"] = item.node.get_content()
            scored.append((chunk, float(item.score or 0.0) * ranking_multiplier(chunk)))
        scored.sort(key=lambda entry: entry[1], reverse=True)
        return scored[:limit]
