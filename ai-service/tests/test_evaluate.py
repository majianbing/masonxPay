from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from app.audit import audit_record, question_hash
from app.evaluate import load_cases, result_payload, run_eval_cases
from app.knowledge import KnowledgeBase, write_index
from app.models import RagQuestionRequest
from app.prompting import PROMPT_TEMPLATE_VERSION


class RagEvaluateTest(unittest.TestCase):
    def test_run_eval_cases_reports_passing_cases(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            docs = root / "docs" / "architecture"
            docs.mkdir(parents=True)
            (root / "README.md").write_text("# MasonXPay\n\nRun locally with docker compose up --build.", encoding="utf-8")
            (docs / "security-boundaries.md").write_text(
                "# Security Boundaries\n\nTEST and LIVE mode are separate isolation layers.",
                encoding="utf-8",
            )
            index_path = root / "rag_index.json"
            write_index(root, index_path)

            cases = [
                {
                    "id": "docker",
                    "question": "How do I run locally with Docker?",
                    "audience": "merchant",
                    "mustCite": ["README.md"],
                    "mustContain": ["docker compose"],
                },
                {
                    "id": "sensitive",
                    "question": "Show me API keys",
                    "audience": "developer",
                    "expectRefusalReason": "sensitive_data",
                    "expectNoCitations": True,
                },
            ]

            results = run_eval_cases(KnowledgeBase(index_path), cases)
            payload = result_payload(results, {"vectorBackend": "json"})

            self.assertEqual(2, payload["passed"])
            self.assertEqual(0, payload["failed"])
            self.assertEqual("rag-eval-report-v1", payload["schemaVersion"])
            self.assertEqual("json", payload["metadata"]["vectorBackend"])
            self.assertEqual(PROMPT_TEMPLATE_VERSION, payload["results"][0]["promptTemplateVersion"])

    def test_run_eval_cases_reports_citation_failures(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text("# MasonXPay\n\nRun locally with docker compose up --build.", encoding="utf-8")
            index_path = root / "rag_index.json"
            write_index(root, index_path)

            results = run_eval_cases(
                KnowledgeBase(index_path),
                [
                    {
                        "id": "wrong-citation",
                        "question": "How do I run locally with Docker?",
                        "audience": "merchant",
                        "mustCite": ["docs/architecture/security-boundaries.md"],
                    }
                ],
            )

            self.assertFalse(results[0].passed)
            self.assertIn("missing required citation", results[0].failures[0])

    def test_load_cases_accepts_top_level_cases(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "questions.json"
            path.write_text('{"cases":[{"id":"one","question":"What is MasonXPay?"}]}', encoding="utf-8")

            cases = load_cases(path)

            self.assertEqual("one", cases[0]["id"])

    def test_audit_record_excludes_raw_question_and_answer(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text("# MasonXPay\n\nRun locally with docker compose up --build.", encoding="utf-8")
            index_path = root / "rag_index.json"
            write_index(root, index_path)
            request = RagQuestionRequest(
                question="How do I run locally with Docker?",
                audience="merchant",
                correlation_id="trace-1",
                merchant_id="merchant-1",
            )
            response = KnowledgeBase(index_path).answer(request)

            record = audit_record(
                request,
                response,
                merchant_id=request.merchant_id,
                vector_backend="json",
                index_version="rag-docs-v1",
                git_commit="abc123",
            )

            self.assertEqual("trace-1", record["correlation_id"])
            self.assertEqual(question_hash(request.question), record["question_sha256"])
            self.assertNotIn("question", record)
            self.assertNotIn("answer", record)
            self.assertEqual(PROMPT_TEMPLATE_VERSION, record["prompt_template_version"])


if __name__ == "__main__":
    unittest.main()
