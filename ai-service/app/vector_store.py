from __future__ import annotations

import hashlib
import math
import threading
import time
from collections import Counter
from typing import Protocol

from app.text import tokenize

VECTOR_SIZE = 384


class Retriever(Protocol):
    def retrieve(self, question: str, audience: str, limit: int) -> list[tuple[dict, float]]:
        ...


def embedding_for_text(text: str, size: int = VECTOR_SIZE) -> list[float]:
    counts: Counter[int] = Counter()
    for token in tokenize(text):
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        index = int.from_bytes(digest[:4], "big") % size
        sign = 1 if digest[4] % 2 == 0 else -1
        counts[index] += sign
    vector = [0.0] * size
    for index, value in counts.items():
        vector[index] = float(value)
    norm = math.sqrt(sum(value * value for value in vector))
    if norm == 0:
        return vector
    return [value / norm for value in vector]


class JsonRetriever:
    def __init__(self, chunks_provider):
        self.chunks_provider = chunks_provider

    def retrieve(self, question: str, audience: str, limit: int) -> list[tuple[dict, float]]:
        query_tokens = tokenize(question)
        if not query_tokens:
            return []
        query_set = set(query_tokens)
        scored: list[tuple[dict, float]] = []
        for chunk in self.chunks_provider():
            if audience not in set(chunk.get("audience", [])):
                continue
            tokens = chunk.get("tokens", [])
            token_set = set(tokens)
            overlap = query_set & token_set
            if not overlap:
                continue
            density = len(overlap) / math.sqrt(max(len(token_set), 1))
            heading_boost = 1.15 if query_set & set(tokenize(chunk.get("heading_path", ""))) else 1.0
            scored.append((chunk, density * ranking_multiplier(chunk) * heading_boost))
        scored.sort(key=lambda item: item[1], reverse=True)
        return scored[:limit]


class QdrantRetriever:
    def __init__(self, url: str, collection_name: str, chunks_provider):
        from qdrant_client import QdrantClient

        self.client = QdrantClient(url=url)
        self.collection_name = collection_name
        self.chunks_provider = chunks_provider
        self._index_synced = False
        self._index_lock = threading.Lock()

    def ensure_indexed(self) -> None:
        """Sync the collection to the current index at most once per process.

        Re-indexing after this is an explicit operation (the ``ingest`` CLI calls
        ``ensure_index`` directly, or the service is restarted), so retrieval stays
        off the write path and does not scroll/count the whole collection per query.
        """
        if self._index_synced:
            return
        with self._index_lock:
            if self._index_synced:
                return
            self.ensure_index()
            self._index_synced = True

    def ensure_index(self) -> None:
        from qdrant_client.models import Distance, PointIdsList, PointStruct, VectorParams

        chunks = self.chunks_provider()
        for attempt in range(1, 6):
            try:
                if not self.client.collection_exists(self.collection_name):
                    self.client.create_collection(
                        collection_name=self.collection_name,
                        vectors_config=VectorParams(size=VECTOR_SIZE, distance=Distance.COSINE),
                    )
                break
            except Exception:
                if attempt == 5:
                    raise
                time.sleep(0.5)
        current_point_ids = {chunk["point_id"] for chunk in chunks}
        stale_point_ids = self.stale_point_ids(current_point_ids)
        if stale_point_ids:
            self.client.delete(
                collection_name=self.collection_name,
                points_selector=PointIdsList(points=stale_point_ids),
                wait=True,
            )
        count = self.client.count(self.collection_name, exact=True).count
        if count == len(chunks) and not stale_point_ids:
            return
        points = [
            PointStruct(
                id=chunk["point_id"],
                vector=embedding_for_text(chunk["text"]),
                payload=chunk,
            )
            for chunk in chunks
        ]
        self.client.upsert(collection_name=self.collection_name, points=points, wait=True)

    def stale_point_ids(self, current_point_ids: set[str]) -> list[str]:
        existing_point_ids: list[str] = []
        offset = None
        while True:
            records, offset = self.client.scroll(
                collection_name=self.collection_name,
                limit=256,
                offset=offset,
                with_payload=["point_id"],
                with_vectors=False,
            )
            for record in records:
                payload_point_id = record.payload.get("point_id") if record.payload else None
                existing_point_ids.append(str(payload_point_id or record.id))
            if offset is None:
                break
        return [point_id for point_id in existing_point_ids if point_id not in current_point_ids]

    def retrieve(self, question: str, audience: str, limit: int) -> list[tuple[dict, float]]:
        from qdrant_client.models import FieldCondition, Filter, MatchValue

        self.ensure_indexed()
        # Over-fetch by raw vector score, then re-rank with the stability/source boost so a
        # stable doc that ranks just outside `limit` can still win over an active-plan chunk.
        candidate_limit = max(limit * 5, 20)
        results = self.client.search(
            collection_name=self.collection_name,
            query_vector=embedding_for_text(question),
            query_filter=Filter(
                must=[
                    FieldCondition(
                        key="audience",
                        match=MatchValue(value=audience),
                    )
                ]
            ),
            limit=candidate_limit,
            with_payload=True,
        )
        scored = [
            (result.payload, float(result.score) * ranking_multiplier(result.payload))
            for result in results
            if result.payload
        ]
        scored.sort(key=lambda item: item[1], reverse=True)
        return scored[:limit]


def ranking_multiplier(chunk: dict) -> float:
    stability = chunk.get("stability")
    source_type = chunk.get("source_type")
    multiplier = 1.0
    if stability == "stable":
        multiplier *= 2.0
    elif stability == "active-plan":
        multiplier *= 0.6
    if source_type in {"architecture", "engineering"}:
        multiplier *= 1.2
    return multiplier
