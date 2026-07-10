from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.knowledge import KnowledgeBase
from app.models import RagQuestionRequest


@dataclass(frozen=True)
class EvalResult:
    case_id: str
    passed: bool
    failures: list[str]
    answer: str
    refusal_reason: str | None
    citations: list[str]
    confidence: str
    prompt_template_version: str
    answer_policy_version: str
    model_provider: str
    model_name: str


def run_eval_cases(kb: KnowledgeBase, cases: list[dict[str, Any]]) -> list[EvalResult]:
    results: list[EvalResult] = []
    for case in cases:
        request = RagQuestionRequest(
            question=case["question"],
            audience=case.get("audience", "merchant"),
            max_citations=case.get("maxCitations", 4),
        )
        response = kb.answer(request)
        citation_paths = [citation.source_path for citation in response.citations]
        failures = validate_case(case, response.refusal_reason, response.answer, citation_paths)
        results.append(
            EvalResult(
                case_id=case["id"],
                passed=not failures,
                failures=failures,
                answer=response.answer,
                refusal_reason=response.refusal_reason,
                citations=citation_paths,
                confidence=response.confidence,
                prompt_template_version=response.prompt_template_version,
                answer_policy_version=response.answer_policy_version,
                model_provider=response.model_provider,
                model_name=response.model_name,
            )
        )
    return results


def validate_case(case: dict[str, Any], refusal_reason: str | None, answer: str, citation_paths: list[str]) -> list[str]:
    failures: list[str] = []
    expected_refusal = case.get("expectRefusalReason")
    if refusal_reason != expected_refusal:
        failures.append(f"expected refusal {expected_refusal!r}, got {refusal_reason!r}")

    for expected in case.get("mustCite", []):
        if expected not in citation_paths:
            failures.append(f"missing required citation {expected!r}")

    for group in case.get("mustCiteAnyOf", []):
        if not any(path in citation_paths for path in group):
            failures.append(f"missing one citation from {group!r}")

    for forbidden in case.get("mustNotCite", []):
        if forbidden in citation_paths:
            failures.append(f"forbidden citation returned {forbidden!r}")

    answer_lower = answer.lower()
    for expected_text in case.get("mustContain", []):
        if expected_text.lower() not in answer_lower:
            failures.append(f"answer missing text {expected_text!r}")

    if case.get("expectNoCitations") and citation_paths:
        failures.append(f"expected no citations, got {citation_paths!r}")

    return failures


def load_cases(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    cases = payload.get("cases") if isinstance(payload, dict) else payload
    if not isinstance(cases, list):
        raise ValueError(f"Eval file must contain a list or a top-level cases list: {path}")
    return cases


def result_payload(results: list[EvalResult], metadata: dict[str, Any] | None = None) -> dict[str, Any]:
    passed = sum(1 for result in results if result.passed)
    return {
        "schemaVersion": "rag-eval-report-v1",
        "generatedAt": datetime.now(UTC).isoformat(),
        "passed": passed,
        "failed": len(results) - passed,
        "total": len(results),
        "metadata": metadata or {},
        "results": [
            {
                "id": result.case_id,
                "passed": result.passed,
                "failures": result.failures,
                "refusalReason": result.refusal_reason,
                "confidence": result.confidence,
                "citations": result.citations,
                "promptTemplateVersion": result.prompt_template_version,
                "answerPolicyVersion": result.answer_policy_version,
                "modelProvider": result.model_provider,
                "modelName": result.model_name,
                "answer": result.answer,
            }
            for result in results
        ],
    }


def build_eval_kb(index_path: Path, repo_root: Path, backend: str) -> KnowledgeBase:
    if backend == "llamaindex":
        from app.llamaindex_retriever import LlamaIndexRetriever

        base = KnowledgeBase(index_path, repo_root)
        return KnowledgeBase(index_path, repo_root, LlamaIndexRetriever(base.chunks))
    return KnowledgeBase(index_path, repo_root)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run MasonXPay RAG golden-question evals.")
    parser.add_argument("--repo-root", default=".", help="Repository root containing approved documentation sources.")
    parser.add_argument("--index-path", default="data/rag_index.json", help="JSON RAG index path.")
    parser.add_argument("--questions", default="evals/golden_questions.json", help="Golden-question JSON file.")
    parser.add_argument("--json-output", action="store_true", help="Print machine-readable JSON report.")
    parser.add_argument("--report-path", help="Write the machine-readable JSON report to this path.")
    parser.add_argument("--backend", default="json", choices=["json", "llamaindex"],
                        help="Retrieval backend to evaluate (framework bakeoff).")
    args = parser.parse_args()

    repo_root = Path(args.repo_root).resolve()
    index_path = Path(args.index_path).resolve()
    question_path = Path(args.questions).resolve()
    kb = build_eval_kb(index_path, repo_root, args.backend)
    results = run_eval_cases(kb, load_cases(question_path))
    status = kb.status(args.backend)
    payload = result_payload(
        results,
        {
            "repoRoot": str(repo_root),
            "indexPath": str(index_path),
            "questionPath": str(question_path),
            "indexVersion": status.index_version,
            "gitCommit": status.git_commit,
            "lastIndexedAt": status.last_indexed_at,
            "chunkCount": status.chunk_count,
            "sourceCount": status.source_count,
            "vectorBackend": status.vector_backend,
            "promptTemplateVersion": status.prompt_template_version,
            "answerPolicyVersion": status.answer_policy_version,
            "modelProvider": status.model_provider,
            "modelName": status.model_name,
        },
    )

    if args.report_path:
        report_path = Path(args.report_path).resolve()
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    if args.json_output:
        print(json.dumps(payload, indent=2))
    else:
        print(f"RAG eval: {payload['passed']}/{payload['total']} passed")
        for result in results:
            status = "PASS" if result.passed else "FAIL"
            print(f"{status} {result.case_id}")
            for failure in result.failures:
                print(f"  - {failure}")
            if not result.passed:
                print(f"  citations: {', '.join(result.citations) or '(none)'}")

    if payload["failed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
