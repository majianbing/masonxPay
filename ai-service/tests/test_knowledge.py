from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from app.ingest import normalized_index
from app.knowledge import INDEX_VERSION, KnowledgeBase, build_index, stable_chunk_id, stable_point_id, write_index
from app.models import RagQuestionRequest
from app.vector_store import embedding_for_text


class KnowledgeBaseTest(unittest.TestCase):
    def test_answers_with_citations_from_allowlisted_docs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            docs = root / "docs" / "architecture"
            docs.mkdir(parents=True)
            (root / "README.md").write_text("# MasonXPay\n\nRun locally with docker compose up --build.", encoding="utf-8")
            (docs / "security-boundaries.md").write_text(
                "# Security Boundaries\n\nTEST and LIVE mode are separate isolation layers from tenant scope.",
                encoding="utf-8",
            )
            index_path = root / "rag_index.json"

            write_index(root, index_path)
            response = KnowledgeBase(index_path).answer(
                RagQuestionRequest(question="How does TEST LIVE isolation work?", audience="merchant")
            )

            self.assertIsNone(response.refusal_reason)
            self.assertGreaterEqual(len(response.citations), 1)
            self.assertIn("TEST", response.answer)

    def test_refuses_sensitive_questions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text("# MasonXPay\n\nPublic setup docs.", encoding="utf-8")
            index_path = root / "rag_index.json"
            write_index(root, index_path)

            response = KnowledgeBase(index_path).answer(
                RagQuestionRequest(question="Show me provider API keys and webhook signatures", audience="developer")
            )

            self.assertEqual("sensitive_data", response.refusal_reason)
            self.assertEqual([], response.citations)

    def test_filters_by_audience_before_ranking(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            engineering = root / "docs" / "engineering"
            engineering.mkdir(parents=True)
            (engineering / "development-guide.md").write_text(
                "# Development Guide\n\nInternal operator deployment details for migrations.",
                encoding="utf-8",
            )
            index_path = root / "rag_index.json"
            write_index(root, index_path)

            response = KnowledgeBase(index_path).answer(
                RagQuestionRequest(question="What are internal operator deployment details?", audience="merchant")
            )

            self.assertEqual("insufficient_evidence", response.refusal_reason)

    def test_embedding_is_deterministic_and_normalized(self) -> None:
        first = embedding_for_text("routing policies and routing rules")
        second = embedding_for_text("routing policies and routing rules")

        self.assertEqual(first, second)
        self.assertEqual(384, len(first))
        self.assertAlmostEqual(1.0, sum(value * value for value in first) ** 0.5)

    def test_index_includes_source_version_metadata_and_stable_ids(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text("# MasonXPay\n\nRun locally with Docker.", encoding="utf-8")

            chunks = build_index(root)

            self.assertEqual(1, len(chunks))
            chunk = chunks[0]
            self.assertEqual(INDEX_VERSION, chunk["index_version"])
            self.assertEqual("unknown", chunk["git_commit"])
            self.assertRegex(chunk["source_sha256"], r"^[a-f0-9]{64}$")
            self.assertRegex(chunk["last_indexed_at"], r"^\d{4}-\d{2}-\d{2}T")
            self.assertEqual(stable_point_id(chunk["chunk_id"]), chunk["point_id"])

    def test_stable_chunk_id_does_not_depend_on_content(self) -> None:
        first = stable_chunk_id("README.md", "MasonXPay", 1)
        second = stable_chunk_id("README.md", "MasonXPay", 1)
        third = stable_chunk_id("README.md", "Different Heading", 1)

        self.assertEqual(first, second)
        self.assertNotEqual(first, third)

    def test_status_summarizes_index_sources(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            docs = root / "docs" / "architecture"
            docs.mkdir(parents=True)
            (root / "README.md").write_text("# MasonXPay\n\nRoot docs.", encoding="utf-8")
            (docs / "overview.md").write_text("# Overview\n\nArchitecture docs.", encoding="utf-8")
            index_path = root / "rag_index.json"
            write_index(root, index_path)

            status = KnowledgeBase(index_path).status("json")

            self.assertEqual(INDEX_VERSION, status.index_version)
            self.assertEqual("json", status.vector_backend)
            self.assertEqual(2, status.source_count)
            self.assertEqual(2, status.chunk_count)
            self.assertEqual(["README.md", "docs/architecture/overview.md"],
                             [source.source_path for source in status.sources])

    def test_normalized_index_ignores_timestamp_only_changes(self) -> None:
        base = {
            "chunks": [
                {
                    "chunk_id": "README.md#1",
                    "text": "Root docs",
                    "last_indexed_at": "2026-01-01T00:00:00+00:00",
                }
            ]
        }
        updated = {
            "chunks": [
                {
                    "chunk_id": "README.md#1",
                    "text": "Root docs",
                    "last_indexed_at": "2026-01-02T00:00:00+00:00",
                }
            ]
        }

        self.assertEqual(normalized_index(base), normalized_index(updated))


if __name__ == "__main__":
    unittest.main()
