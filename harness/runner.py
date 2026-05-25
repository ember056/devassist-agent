#!/usr/bin/env python3
import argparse
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def load_cases(cases_dir: Path):
    cases = []
    for path in sorted(cases_dir.glob("*.json")):
        with path.open("r", encoding="utf-8") as f:
            case = json.load(f)
        case["_path"] = str(path)
        cases.append(case)
    return cases


def post_json(url: str, payload: dict, timeout: int):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json"
        },
        method="POST",
    )
    with urlopen(request, timeout=timeout) as response:
        raw = response.read().decode("utf-8")
        return response.status, raw


def extract_answer(response_text: str):
    try:
        response = json.loads(response_text)
    except json.JSONDecodeError:
        return response_text

    data = response.get("data")
    if isinstance(data, dict):
        return data.get("answer") or data.get("errorMessage") or json.dumps(data, ensure_ascii=False)
    return json.dumps(response, ensure_ascii=False)


def evaluate_assertions(answer: str, assertions: dict):
    failures = []

    for expected in assertions.get("contains", []):
        if expected not in answer:
            failures.append(f"missing expected text: {expected}")

    for forbidden in assertions.get("not_contains", []):
        if forbidden in answer:
            failures.append(f"found forbidden text: {forbidden}")

    for pattern in assertions.get("regex", []):
        if not re.search(pattern, answer, flags=re.MULTILINE):
            failures.append(f"regex not matched: {pattern}")

    return failures


def run_case(case: dict, base_url: str, timeout: int):
    endpoint = case.get("endpoint", "/api/chat")
    url = base_url.rstrip("/") + endpoint
    started = time.time()

    try:
        status, response_text = post_json(url, case.get("payload", {}), timeout)
        answer = extract_answer(response_text)
        failures = evaluate_assertions(answer, case.get("assertions", {}))
        passed = status == 200 and not failures
        if status != 200:
            failures.append(f"http status is {status}")
    except (HTTPError, URLError, TimeoutError, Exception) as exc:
        status = None
        response_text = ""
        answer = ""
        failures = [f"request failed: {exc}"]
        passed = False

    duration_ms = int((time.time() - started) * 1000)
    return {
        "id": case.get("id"),
        "name": case.get("name"),
        "path": case.get("_path"),
        "endpoint": endpoint,
        "passed": passed,
        "durationMs": duration_ms,
        "status": status,
        "failures": failures,
        "answerPreview": answer[:800],
    }


def write_report(report: dict, output: Path):
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)


def main():
    parser = argparse.ArgumentParser(description="DevAssist RAG/Agent harness runner")
    parser.add_argument("--base-url", default="http://localhost:9900")
    parser.add_argument("--cases", default=str(Path(__file__).parent / "cases"))
    parser.add_argument("--output", default=str(Path(__file__).parent / "reports" / "latest-report.json"))
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()

    cases_dir = Path(args.cases)
    output = Path(args.output)
    cases = load_cases(cases_dir)

    if not cases:
        print(f"No cases found in {cases_dir}", file=sys.stderr)
        return 2

    results = []
    for case in cases:
        print(f"[RUN] {case.get('id')} - {case.get('name')}")
        result = run_case(case, args.base_url, args.timeout)
        results.append(result)
        status = "PASS" if result["passed"] else "FAIL"
        print(f"[{status}] {case.get('id')} ({result['durationMs']} ms)")
        for failure in result["failures"]:
            print(f"  - {failure}")

    passed_count = sum(1 for result in results if result["passed"])
    failed_count = len(results) - passed_count
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "total": len(results),
        "passed": passed_count,
        "failed": failed_count,
        "results": results,
    }
    write_report(report, output)

    print()
    print(f"Total: {len(results)}, Passed: {passed_count}, Failed: {failed_count}")
    print(f"Report: {output}")

    return 0 if failed_count == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
