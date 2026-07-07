from __future__ import annotations

from typing import Protocol, runtime_checkable


class LlmError(RuntimeError):
    """Raised when an external model call cannot produce an answer.

    Callers treat this as a signal to fall back to the local extractive answer,
    so an external-provider outage never takes the assistant down (no-external-AI
    fallback).
    """


@runtime_checkable
class LlmClient(Protocol):
    provider: str
    model: str

    def generate(self, system_prompt: str, user_prompt: str) -> str:
        ...


class OpenAiClient:
    """Thin adapter over the OpenAI Chat Completions API.

    Only approved, already-retrieved documentation text and the user question are
    sent to the provider; retrieval (and its sensitive-data/audience filtering)
    happens before this is ever called.
    """

    def __init__(
        self,
        model: str,
        api_key: str,
        *,
        base_url: str | None = None,
        timeout: float = 30.0,
        max_tokens: int = 700,
        temperature: float = 0.2,
    ) -> None:
        self.provider = "openai"
        self.model = model
        self._api_key = api_key
        self._base_url = base_url
        self._timeout = timeout
        self._max_tokens = max_tokens
        self._temperature = temperature
        self._client = None

    def _ensure_client(self):
        if self._client is None:
            try:
                from openai import OpenAI
            except ImportError as exc:  # SDK missing -> fall back to extractive
                raise LlmError("openai package is not installed") from exc
            self._client = OpenAI(api_key=self._api_key, base_url=self._base_url, timeout=self._timeout)
        return self._client

    def generate(self, system_prompt: str, user_prompt: str) -> str:
        client = self._ensure_client()
        try:
            response = client.chat.completions.create(
                model=self.model,
                temperature=self._temperature,
                max_tokens=self._max_tokens,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
            )
        except Exception as exc:  # network/provider error -> clear fallback in caller
            raise LlmError(f"OpenAI request failed: {exc}") from exc
        content = (response.choices[0].message.content or "").strip()
        if not content:
            raise LlmError("OpenAI returned an empty answer")
        return content


def create_llm_client(
    provider: str,
    model: str,
    api_key: str,
    *,
    base_url: str | None = None,
) -> LlmClient | None:
    """Build the configured model client, or None for the local no-external-AI mode."""
    provider = (provider or "local").lower()
    if provider == "local":
        return None
    if provider == "openai":
        if not api_key:
            raise LlmError("RAG_MODEL_PROVIDER=openai requires OPENAI_API_KEY")
        return OpenAiClient(model=model, api_key=api_key, base_url=base_url)
    raise LlmError(f"Unsupported RAG_MODEL_PROVIDER: {provider!r} (supported: local, openai)")
