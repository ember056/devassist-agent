#!/usr/bin/env python3
import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
COMMON_SCORE_KEYS = [
    "faithfulness",
    "relevance",
    "completeness",
    "citationQuality",
    "actionability",
    "riskControl",
]
AIOPS_SCORE_KEYS = [
    "rootCauseReasoning",
    "evidenceAlignment",
    "contradictionHandling",
    "reportStructure",
]


def read_json(path: Path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def write_json(path: Path, value: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2)


def read_prompt(prompt_dir: Path, category: str):
    prompt_name = "aiops_judge_prompt.md" if category == "aiops" else "rag_judge_prompt.md"
    return (prompt_dir / prompt_name).read_text(encoding="utf-8")


def build_case_payload(result: dict):
    return {
        "caseId": result.get("id"),
        "name": result.get("name"),
        "category": result.get("category"),
        "endpoint": result.get("endpoint"),
        "question": result.get("question") or "",
        "labels": result.get("labels", {}),
        "labelMetrics": result.get("labelMetrics", {}),
        "passedByRules": result.get("passed"),
        "durationMs": result.get("durationMs"),
        "failures": result.get("failures", []),
        "answer": result.get("answer") or result.get("answerPreview") or "",
        "answerPreview": result.get("answerPreview") or "",
        "capabilities": result.get("capabilities", []),
    }


def call_openai_compatible(endpoint: str, api_key: str, model: str, prompt: str, payload: dict, timeout: int):
    body = json.dumps({
        "model": model,
        "temperature": 0.0,
        "messages": [
            {"role": "system", "content": prompt},
            {"role": "user", "content": json.dumps(payload, ensure_ascii=False, indent=2)}
        ]
    }, ensure_ascii=False).encode("utf-8")

    request = Request(
        endpoint,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )
    with urlopen(request, timeout=timeout) as response:
        raw = response.read().decode("utf-8")
    parsed = json.loads(raw)
    return parsed["choices"][0]["message"]["content"]


def extract_json(text: str):
    if text is None:
        raise ValueError("empty judge response")
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("judge response does not contain JSON object")
    return json.loads(text[start:end + 1])


def normalize_score(value):
    try:
        score = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, min(5.0, score))


def normalize_judge_result(raw: dict, category: str, threshold: float):
    scores = raw.get("scores") if isinstance(raw.get("scores"), dict) else {}
    required_keys = list(COMMON_SCORE_KEYS)
    if category == "aiops":
        required_keys.extend(AIOPS_SCORE_KEYS)

    normalized_scores = {key: normalize_score(scores.get(key)) for key in required_keys}
    non_zero = [score for score in normalized_scores.values() if score > 0]
    fallback_overall = sum(non_zero) / len(non_zero) if non_zero else 0.0
    overall = normalize_score(raw.get("overallScore", fallback_overall))

    critical_issues = raw.get("criticalIssues") if isinstance(raw.get("criticalIssues"), list) else []
    unsupported_claims = raw.get("unsupportedClaims") if isinstance(raw.get("unsupportedClaims"), list) else []
    passed = bool(raw.get("passed", overall >= threshold and not critical_issues))

    return {
        "judgeStatus": "SUCCESS",
        "overallScore": round(overall, 4),
        "passed": passed and overall >= threshold and not critical_issues,
        "scores": normalized_scores,
        "reason": str(raw.get("reason", "")).strip(),
        "criticalIssues": [str(item) for item in critical_issues],
        "unsupportedClaims": [str(item) for item in unsupported_claims],
    }


def failed_judge_result(error: str):
    return {
        "judgeStatus": "FAILED",
        "overallScore": 0.0,
        "passed": False,
        "scores": {},
        "reason": error,
        "criticalIssues": [error],
        "unsupportedClaims": [],
    }


def judge_case(result: dict, prompt_dir: Path, endpoint: str, api_key: str, model: str, timeout: int, threshold: float, dry_run: bool):
    category = result.get("category") or "rag"
    prompt = read_prompt(prompt_dir, category)
    payload = build_case_payload(result)

    if dry_run:
        return {
            "judgeStatus": "DRY_RUN",
            "overallScore": 0.0,
            "passed": False,
            "scores": {},
            "reason": "dry-run: judge call skipped",
            "criticalIssues": [],
            "unsupportedClaims": [],
            "promptPreview": prompt[:300],
        }

    last_error = None
    for _ in range(2):
        try:
            response = call_openai_compatible(endpoint, api_key, model, prompt, payload, timeout)
            raw_result = extract_json(response)
            return normalize_judge_result(raw_result, category, threshold)
        except (HTTPError, URLError, TimeoutError, KeyError, ValueError, json.JSONDecodeError, Exception) as exc:
            last_error = str(exc)
            time.sleep(1)
    return failed_judge_result(last_error or "unknown judge failure")


def summarize(results: list):
    judged = [item for item in results if item["judgeResult"]["judgeStatus"] == "SUCCESS"]
    failed = [item for item in results if item["judgeResult"]["judgeStatus"] == "FAILED"]
    passed = [item for item in judged if item["judgeResult"]["passed"]]
    score_keys = set()
    for item in judged:
        score_keys.update(item["judgeResult"].get("scores", {}).keys())

    averages = {}
    for key in sorted(score_keys):
        values = [item["judgeResult"]["scores"].get(key, 0.0) for item in judged if key in item["judgeResult"].get("scores", {})]
        averages[key] = round(sum(values) / len(values), 4) if values else 0.0

    unsupported_count = sum(len(item["judgeResult"].get("unsupportedClaims", [])) for item in judged)
    critical_count = sum(len(item["judgeResult"].get("criticalIssues", [])) for item in judged)
    avg_overall = sum(item["judgeResult"]["overallScore"] for item in judged) / len(judged) if judged else 0.0

    return {
        "total": len(results),
        "judgeSuccess": len(judged),
        "judgeFailed": len(failed),
        "judgePassed": len(passed),
        "judgePassRate": round(len(passed) / len(judged), 4) if judged else 0.0,
        "averageOverallScore": round(avg_overall, 4),
        "averageScores": averages,
        "unsupportedClaimCount": unsupported_count,
        "criticalIssueCount": critical_count,
        "lowScoreCases": [
            item["id"] for item in judged
            if item["judgeResult"]["overallScore"] < 4.0 or not item["judgeResult"]["passed"]
        ],
        "judgeParseFailureCount": len(failed),
    }


def estimate_cost(results: list):
    # Rough character-based estimate; avoids pretending exact token accounting.
    total_chars = 0
    for item in results:
        total_chars += len(item.get("answer") or item.get("answerPreview") or "")
        total_chars += len(json.dumps(item.get("judgeResult", {}), ensure_ascii=False))
    return {
        "estimatedChars": total_chars,
        "note": "Rough character count only; use provider billing logs for exact token cost."
    }


def main():
    parser = argparse.ArgumentParser(description="Offline LLM-as-Judge runner for DevAssist harness reports")
    parser.add_argument("--input", required=True, help="Input harness report JSON")
    parser.add_argument("--output", default=str(Path(__file__).parent / "benchmark" / "reports" / "judge-report.json"))
    parser.add_argument("--prompt-dir", default=str(Path(__file__).parent / "judge_prompts"))
    parser.add_argument("--endpoint", default=os.getenv("DASHSCOPE_JUDGE_ENDPOINT", DEFAULT_ENDPOINT))
    parser.add_argument("--model", default=os.getenv("DASHSCOPE_JUDGE_MODEL", "qwen-plus"))
    parser.add_argument("--api-key", default=os.getenv("DASHSCOPE_API_KEY", ""))
    parser.add_argument("--timeout", type=int, default=int(os.getenv("DASHSCOPE_JUDGE_TIMEOUT", "60")))
    parser.add_argument("--pass-threshold", type=float, default=4.0)
    parser.add_argument("--max-cases", type=int, default=30)
    parser.add_argument("--category", help="Judge only selected categories, comma-separated")
    parser.add_argument("--dry-run", action="store_true", help="Build judge inputs without calling the model")
    args = parser.parse_args()

    if not args.dry_run and not args.api_key:
        print("DASHSCOPE_API_KEY is required unless --dry-run is used.", file=sys.stderr)
        return 2

    report = read_json(Path(args.input))
    results = report.get("results", [])
    if args.category:
        wanted = {item.strip() for item in args.category.split(",") if item.strip()}
        results = [item for item in results if item.get("category") in wanted]
    results = results[:max(1, args.max_cases)]

    judged_results = []
    for result in results:
        print(f"[JUDGE] {result.get('id')} [{result.get('category')}]")
        judge_result = judge_case(
            result,
            Path(args.prompt_dir),
            args.endpoint,
            args.api_key,
            args.model,
            args.timeout,
            args.pass_threshold,
            args.dry_run,
        )
        merged = dict(result)
        merged["judgeResult"] = judge_result
        judged_results.append(merged)
        print(f"  - {judge_result['judgeStatus']} score={judge_result['overallScore']} passed={judge_result['passed']}")

    summary = summarize(judged_results)
    output = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "inputReport": str(args.input),
        "model": args.model,
        "endpoint": args.endpoint,
        "passThreshold": args.pass_threshold,
        "dryRun": args.dry_run,
        "summary": summary,
        "costEstimate": estimate_cost(judged_results),
        "results": judged_results,
    }
    write_json(Path(args.output), output)
    print()
    print(f"Judge Pass Rate: {summary['judgePassRate']}")
    print(f"Average Overall Score: {summary['averageOverallScore']}")
    print(f"Report: {args.output}")
    return 0 if summary["judgeFailed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
