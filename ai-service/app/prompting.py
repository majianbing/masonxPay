from __future__ import annotations

PROMPT_TEMPLATE_VERSION = "rag-answer-template-v1"
ANSWER_POLICY_VERSION = "rag-answer-policy-v1"
MODEL_PROVIDER = "local"
MODEL_NAME = "deterministic-extractive-v1"

SYSTEM_PROMPT = (
    "Answer MasonXPay support questions only from approved retrieved documentation. "
    "Cite sources, prefer stable architecture and engineering docs over planning docs, "
    "respect audience filtering, and refuse unsupported or sensitive-data requests."
)
