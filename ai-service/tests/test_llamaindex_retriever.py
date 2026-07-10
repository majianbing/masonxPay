from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from app.knowledge import KnowledgeBase, write_index
from app.llamaindex_retriever import LlamaIndexRetriever
from app.models import RagQuestionRequest


def _kb(root: Path) -> KnowledgeBase:
    index_path = root / "rag_index.json"
    write_index(root, index_path)
    base = KnowledgeBase(index_path, root)
    return KnowledgeBase(index_path, root, LlamaIndexRetriever(base.chunks))


class LlamaIndexRetrieverTest(unittest.TestCase):
    def test_answers_with_citations_and_tuple_shape(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs" / "architecture").mkdir(parents=True)
            (root / "docs" / "architecture" / "overview.md").write_text(
                "# Overview\n\nMasonXPay keeps TEST and LIVE mode isolation as a separate boundary from tenant isolation.",
                encoding="utf-8",
            )
            kb = _kb(root)

            response = kb.answer(RagQuestionRequest(question="How does TEST and LIVE mode isolation work?", audience="merchant"))

            self.assertIsNone(response.refusal_reason)
            self.assertGreaterEqual(len(response.citations), 1)
            self.assertEqual("docs/architecture/overview.md", response.citations[0].source_path)

    def test_does_not_return_chunks_outside_requester_audience(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs" / "engineering").mkdir(parents=True)
            # engineering docs are NOT in the `merchant` audience set.
            (root / "docs" / "engineering" / "rag-operations.md").write_text(
                "# RAG operations\n\nOperators rotate the Qdrant API key and TLS certificates during deployment.",
                encoding="utf-8",
            )
            kb = _kb(root)

            matches = kb.retrieve("How do operators rotate the Qdrant API key?", "merchant", 4)

            self.assertEqual([], matches)

    def test_stable_docs_rank_above_conflicting_planning_docs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs" / "architecture").mkdir(parents=True)
            (root / "docs" / "planning").mkdir(parents=True)
            (root / "docs" / "architecture" / "routing-orchestration.md").write_text(
                "# Routing\n\nCross-provider fallback requires a portable instrument or explicit customer re-authorization.",
                encoding="utf-8",
            )
            (root / "docs" / "planning" / "old-routing-plan.md").write_text(
                "# Routing Plan\n\nCross-provider fallback does not require explicit customer re-authorization.",
                encoding="utf-8",
            )
            kb = _kb(root)

            response = kb.answer(
                RagQuestionRequest(
                    question="Does cross-provider fallback require customer re-authorization?",
                    audience="developer",
                )
            )

            self.assertEqual("docs/architecture/routing-orchestration.md", response.citations[0].source_path)


if __name__ == "__main__":
    unittest.main()
