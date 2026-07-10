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


def build_answer_prompt(question: str, chunks: list[dict]) -> str:
    """Assemble the user prompt from already-retrieved, approved doc chunks.

    The excerpts are the only knowledge the model may use; citations are still
    derived from retrieval, not from anything the model returns.
    """
    blocks: list[str] = []
    for index, chunk in enumerate(chunks, start=1):
        source = chunk.get("source_path", "unknown")
        heading = chunk.get("heading_path", "").strip()
        location = f"{source} > {heading}" if heading else source
        text = (chunk.get("text") or "").strip()
        blocks.append(f"[{index}] {location}\n{text}")
    context = "\n\n".join(blocks)
    return (
        "Answer the question using ONLY the numbered MasonXPay documentation excerpts below. "
        "If they do not contain the answer, say you do not have enough approved documentation "
        "rather than guessing. Do not invent sources or facts. Be concise and format with Markdown.\n\n"
        f"Question: {question}\n\n"
        f"Documentation excerpts:\n{context}"
    )
