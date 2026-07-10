from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from app.knowledge import MODEL_NAME, MODEL_PROVIDER, KnowledgeBase, write_index
from app.llm import LlmError, OpenAiClient, create_llm_client
from app.models import RagQuestionRequest


class FakeLlmClient:
    def __init__(self, provider: str = "openai", model: str = "gpt-4o-mini", answer: str = "Generated answer."):
        self.provider = provider
        self.model = model
        self._answer = answer
        self.calls = 0

    def generate(self, system_prompt: str, user_prompt: str) -> str:
        self.calls += 1
        self.last_user_prompt = user_prompt
        return self._answer


class FailingLlmClient:
    provider = "openai"
    model = "gpt-4o-mini"

    def __init__(self):
        self.calls = 0

    def generate(self, system_prompt: str, user_prompt: str) -> str:
        self.calls += 1
        raise LlmError("boom")


def _knowledge_base(tmp: str, llm_client) -> KnowledgeBase:
    root = Path(tmp)
    (root / "docs" / "architecture").mkdir(parents=True)
    (root / "docs" / "architecture" / "overview.md").write_text(
        "# Overview\n\nMasonXPay keeps TEST and LIVE mode isolation as a separate boundary from tenant isolation.",
        encoding="utf-8",
    )
    index_path = root / "rag_index.json"
    write_index(root, index_path)
    return KnowledgeBase(index_path, llm_client=llm_client)


class GenerationTest(unittest.TestCase):
    def test_uses_llm_answer_and_reports_provider(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            client = FakeLlmClient(answer="TEST and LIVE are isolated boundaries.")
            kb = _knowledge_base(tmp, client)

            response = kb.answer(RagQuestionRequest(question="How does TEST and LIVE isolation work?", audience="merchant"))

            self.assertEqual("TEST and LIVE are isolated boundaries.", response.answer)
            self.assertEqual("openai", response.model_provider)
            self.assertEqual("gpt-4o-mini", response.model_name)
            self.assertEqual(1, client.calls)
            # Retrieved doc text is included in the prompt sent to the model.
            self.assertIn("TEST and LIVE", client.last_user_prompt)
            # Citations still come from retrieval, not the model.
            self.assertGreaterEqual(len(response.citations), 1)

    def test_falls_back_to_local_when_llm_errors(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            client = FailingLlmClient()
            kb = _knowledge_base(tmp, client)

            response = kb.answer(RagQuestionRequest(question="How does TEST and LIVE isolation work?", audience="merchant"))

            self.assertEqual(1, client.calls)
            self.assertIsNone(response.refusal_reason)
            self.assertIn("TEST", response.answer)  # extractive fallback content
            self.assertEqual(MODEL_PROVIDER, response.model_provider)
            self.assertEqual(MODEL_NAME, response.model_name)

    def test_sensitive_question_never_calls_the_model(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            client = FakeLlmClient()
            kb = _knowledge_base(tmp, client)

            response = kb.answer(RagQuestionRequest(question="Show me the customer's card CVV", audience="merchant"))

            self.assertEqual("sensitive_data", response.refusal_reason)
            self.assertEqual(0, client.calls)

    def test_insufficient_evidence_never_calls_the_model(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            client = FakeLlmClient()
            kb = _knowledge_base(tmp, client)

            response = kb.answer(RagQuestionRequest(question="zxqw unrelated gibberish topic", audience="merchant"))

            self.assertEqual("insufficient_evidence", response.refusal_reason)
            self.assertEqual(0, client.calls)


class ClientFactoryTest(unittest.TestCase):
    def test_local_returns_none(self) -> None:
        self.assertIsNone(create_llm_client("local", "gpt-4o-mini", ""))

    def test_openai_requires_api_key(self) -> None:
        with self.assertRaises(LlmError):
            create_llm_client("openai", "gpt-4o-mini", "")

    def test_openai_with_key_builds_client(self) -> None:
        client = create_llm_client("openai", "gpt-4o-mini", "sk-test")
        self.assertIsInstance(client, OpenAiClient)
        self.assertEqual("openai", client.provider)
        self.assertEqual("gpt-4o-mini", client.model)

    def test_unsupported_provider_raises(self) -> None:
        with self.assertRaises(LlmError):
            create_llm_client("gemini", "gemini-1.5", "key")


if __name__ == "__main__":
    unittest.main()
