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


KNOWN_ASSERTIONS = {
    "contains",
    "not_contains",
    "regex",
    "json_path_exists",
    "json_path_equals",
    "sse_types",
    "sse_trace_id",
    "sse_task_id",
    "max_duration_ms",
}


def get_case_question(case: dict):
    payload = case.get("payload", {})
    if not isinstance(payload, dict):
        return ""
    return payload.get("Question") or payload.get("question") or payload.get("userRequest") or ""


def load_cases(cases_dir: Path):
    cases = []
    for path in sorted(cases_dir.glob("*.json")):
        with path.open("r", encoding="utf-8") as f:
            case = json.load(f)
        case["_path"] = str(path)
        cases.append(case)
    return cases


def validate_case(case: dict):
    errors = []
    case_id = case.get("id", "<missing id>")

    for field in ["id", "name", "category", "endpoint", "payload", "assertions"]:
        if field not in case:
            errors.append(f"{case_id}: missing required field '{field}'")

    if "capabilities" not in case or not isinstance(case.get("capabilities"), list) or not case.get("capabilities"):
        errors.append(f"{case_id}: capabilities must be a non-empty list")

    if "payload" in case and not isinstance(case.get("payload"), dict):
        errors.append(f"{case_id}: payload must be an object")

    labels = case.get("labels", {})
    if labels and not isinstance(labels, dict):
        errors.append(f"{case_id}: labels must be an object")
    elif isinstance(labels, dict):
        for list_field in ["expectedSources", "expectedEvidence", "expectedSections"]:
            if list_field in labels and not isinstance(labels.get(list_field), list):
                errors.append(f"{case_id}: labels.{list_field} must be a list")
        if "expectedRootCause" in labels and not isinstance(labels.get("expectedRootCause"), str):
            errors.append(f"{case_id}: labels.expectedRootCause must be a string")

    assertions = case.get("assertions", {})
    if not isinstance(assertions, dict):
        errors.append(f"{case_id}: assertions must be an object")
        return errors

    unknown_assertions = sorted(set(assertions.keys()) - KNOWN_ASSERTIONS)
    for assertion in unknown_assertions:
        errors.append(f"{case_id}: unknown assertion '{assertion}'")

    for list_field in ["contains", "not_contains", "regex", "json_path_exists", "sse_types"]:
        if list_field in assertions and not isinstance(assertions.get(list_field), list):
            errors.append(f"{case_id}: assertion '{list_field}' must be a list")

    if "json_path_equals" in assertions and not isinstance(assertions.get("json_path_equals"), dict):
        errors.append(f"{case_id}: assertion 'json_path_equals' must be an object")

    if "max_duration_ms" in assertions:
        try:
            int(assertions.get("max_duration_ms"))
        except (TypeError, ValueError):
            errors.append(f"{case_id}: assertion 'max_duration_ms' must be an integer")

    return errors


def validate_cases(cases: list):
    errors = []
    seen_ids = set()
    for case in cases:
        case_id = case.get("id")
        if case_id in seen_ids:
            errors.append(f"{case_id}: duplicate case id")
        seen_ids.add(case_id)
        errors.extend(validate_case(case))
    return errors


def filter_cases(cases: list, category: str):
    if not category:
        return cases
    wanted = {item.strip() for item in category.split(",") if item.strip()}
    return [case for case in cases if case.get("category") in wanted]


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
        return response.status, raw, response.headers.get("Content-Type", "")


def parse_sse_response(response_text: str):
    messages = []
    content_chunks = []
    task_id = None
    trace_id = None
    errors = []

    for block in response_text.replace("\r\n", "\n").split("\n\n"):
        data_lines = []
        for line in block.splitlines():
            if line.startswith("data:"):
                data_lines.append(line[len("data:"):].strip())
        if not data_lines:
            continue

        data = "\n".join(data_lines)
        try:
            message = json.loads(data)
        except json.JSONDecodeError:
            message = {"type": "raw", "data": data}

        messages.append(message)
        message_type = message.get("type")
        if message_type == "content":
            content_chunks.append(message.get("data") or "")
        elif message_type == "task":
            task_id = message.get("taskId") or message.get("data")
            trace_id = message.get("traceId")
        elif message_type == "trace":
            trace_id = message.get("traceId") or message.get("data")
        elif message_type == "error":
            errors.append(message.get("data") or "unknown SSE error")

    return {
        "messages": messages,
        "content": "".join(content_chunks),
        "taskId": task_id,
        "traceId": trace_id,
        "types": [message.get("type") for message in messages if message.get("type")],
        "errors": errors,
    }


def parse_response(response_text: str, content_type: str):
    model = {
        "raw": response_text,
        "contentType": content_type,
        "json": None,
        "sse": None,
        "answer": response_text,
    }

    if "text/event-stream" in content_type or "data:" in response_text[:200]:
        sse = parse_sse_response(response_text)
        model["sse"] = sse
        model["answer"] = sse["content"] or response_text
        return model

    try:
        response = json.loads(response_text)
    except json.JSONDecodeError:
        return model

    model["json"] = response
    data = response.get("data")
    if isinstance(data, dict):
        model["answer"] = data.get("answer") or data.get("errorMessage") or json.dumps(data, ensure_ascii=False)
    else:
        model["answer"] = json.dumps(response, ensure_ascii=False)
    return model


def get_path(value, path: str):
    current = value
    for part in path.split("."):
        if isinstance(current, dict):
            if part not in current:
                return None
            current = current[part]
        elif isinstance(current, list):
            try:
                current = current[int(part)]
            except (ValueError, IndexError):
                return None
        else:
            return None
    return current


def evaluate_assertions(response_model: dict, assertions: dict, duration_ms: int):
    failures = []
    answer = response_model.get("answer", "")

    for expected in assertions.get("contains", []):
        if expected not in answer:
            failures.append(f"missing expected text: {expected}")

    for forbidden in assertions.get("not_contains", []):
        if forbidden in answer:
            failures.append(f"found forbidden text: {forbidden}")

    for pattern in assertions.get("regex", []):
        if not re.search(pattern, answer, flags=re.MULTILINE):
            failures.append(f"regex not matched: {pattern}")

    max_duration_ms = assertions.get("max_duration_ms")
    if max_duration_ms is not None and duration_ms > int(max_duration_ms):
        failures.append(f"duration {duration_ms} ms exceeded max_duration_ms {max_duration_ms}")

    response_json = response_model.get("json")
    for path in assertions.get("json_path_exists", []):
        if response_json is None or get_path(response_json, path) is None:
            failures.append(f"json path missing: {path}")

    for path, expected in assertions.get("json_path_equals", {}).items():
        actual = get_path(response_json, path) if response_json is not None else None
        if actual != expected:
            failures.append(f"json path {path} expected {expected!r}, got {actual!r}")

    sse = response_model.get("sse") or {}
    for expected_type in assertions.get("sse_types", []):
        if expected_type not in sse.get("types", []):
            failures.append(f"sse type missing: {expected_type}")

    if assertions.get("sse_trace_id") and not sse.get("traceId"):
        failures.append("sse traceId missing")

    if assertions.get("sse_task_id") and not sse.get("taskId"):
        failures.append("sse taskId missing")

    for error in sse.get("errors", []):
        failures.append(f"sse error: {error}")

    return failures


def evaluate_labels(answer: str, labels: dict):
    if not isinstance(labels, dict):
        labels = {}

    expected_sources = labels.get("expectedSources", [])
    expected_evidence = labels.get("expectedEvidence", [])
    expected_sections = labels.get("expectedSections", [])
    expected_root_cause = labels.get("expectedRootCause", "")

    source_hits = [source for source in expected_sources if source and source in answer]
    evidence_hits = [evidence for evidence in expected_evidence if evidence and evidence in answer]
    section_hits = [section for section in expected_sections if section and section in answer]
    root_cause_hit = bool(expected_root_cause and expected_root_cause in answer)

    failures = []
    if expected_sources and not source_hits:
        failures.append("expected source not found: " + ", ".join(expected_sources))
    if expected_root_cause and not root_cause_hit:
        failures.append(f"expected root cause not found: {expected_root_cause}")
    if expected_sections and len(section_hits) < len(expected_sections):
        missing = [section for section in expected_sections if section not in section_hits]
        failures.append("expected section not found: " + ", ".join(missing))

    return {
        "expectedSources": expected_sources,
        "sourceHits": source_hits,
        "sourceHit": not expected_sources or bool(source_hits),
        "expectedRootCause": expected_root_cause,
        "rootCauseHit": not expected_root_cause or root_cause_hit,
        "expectedEvidence": expected_evidence,
        "evidenceHits": evidence_hits,
        "expectedSections": expected_sections,
        "sectionHits": section_hits,
        "structureHit": not expected_sections or len(section_hits) == len(expected_sections),
        "failures": failures,
    }


def run_case(case: dict, base_url: str, timeout: int):
    endpoint = case.get("endpoint", "/api/chat")
    url = base_url.rstrip("/") + endpoint
    started = time.time()

    try:
        status, response_text, content_type = post_json(url, case.get("payload", {}), timeout)
        duration_ms = int((time.time() - started) * 1000)
        response_model = parse_response(response_text, content_type)
        answer = response_model.get("answer", "")
        failures = evaluate_assertions(response_model, case.get("assertions", {}), duration_ms)
        label_metrics = evaluate_labels(answer, case.get("labels", {}))
        failures.extend(label_metrics["failures"])
        passed = status == 200 and not failures
        if status != 200:
            failures.append(f"http status is {status}")
    except (HTTPError, URLError, TimeoutError, Exception) as exc:
        status = None
        content_type = ""
        response_text = ""
        answer = ""
        failures = [f"request failed: {exc}"]
        label_metrics = evaluate_labels(answer, case.get("labels", {}))
        passed = False

    duration_ms = int((time.time() - started) * 1000)
    return {
        "id": case.get("id"),
        "name": case.get("name"),
        "category": case.get("category"),
        "capabilities": case.get("capabilities", []),
        "question": get_case_question(case),
        "labels": case.get("labels", {}),
        "labelMetrics": label_metrics,
        "path": case.get("_path"),
        "endpoint": endpoint,
        "passed": passed,
        "durationMs": duration_ms,
        "status": status,
        "contentType": content_type,
        "failures": failures,
        "answer": answer,
        "answerPreview": answer[:800],
    }


def write_report(report: dict, output: Path):
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)


def summarize_results(results: list):
    by_category = {}
    capabilities = {}
    label_totals = {
        "sourceLabelTotal": 0,
        "sourceHit": 0,
        "rootCauseLabelTotal": 0,
        "rootCauseHit": 0,
        "structureLabelTotal": 0,
        "structureHit": 0,
    }
    for result in results:
        category = result.get("category") or "uncategorized"
        category_summary = by_category.setdefault(category, {"total": 0, "passed": 0, "failed": 0})
        category_summary["total"] += 1
        if result.get("passed"):
            category_summary["passed"] += 1
        else:
            category_summary["failed"] += 1

        for capability in result.get("capabilities", []):
            capability_summary = capabilities.setdefault(capability, {"total": 0, "passed": 0, "failed": 0})
            capability_summary["total"] += 1
            if result.get("passed"):
                capability_summary["passed"] += 1
            else:
                capability_summary["failed"] += 1

        label_metrics = result.get("labelMetrics", {})
        if label_metrics.get("expectedSources"):
            label_totals["sourceLabelTotal"] += 1
            if label_metrics.get("sourceHit"):
                label_totals["sourceHit"] += 1
        if label_metrics.get("expectedRootCause"):
            label_totals["rootCauseLabelTotal"] += 1
            if label_metrics.get("rootCauseHit"):
                label_totals["rootCauseHit"] += 1
        if label_metrics.get("expectedSections"):
            label_totals["structureLabelTotal"] += 1
            if label_metrics.get("structureHit"):
                label_totals["structureHit"] += 1

    label_summary = {
        **label_totals,
        "sourceHitRate": round(label_totals["sourceHit"] / label_totals["sourceLabelTotal"], 4)
        if label_totals["sourceLabelTotal"] else 0.0,
        "rootCauseHitRate": round(label_totals["rootCauseHit"] / label_totals["rootCauseLabelTotal"], 4)
        if label_totals["rootCauseLabelTotal"] else 0.0,
        "structureHitRate": round(label_totals["structureHit"] / label_totals["structureLabelTotal"], 4)
        if label_totals["structureLabelTotal"] else 0.0,
    }

    return by_category, capabilities, label_summary


def print_case_list(cases: list):
    for case in cases:
        capabilities = ", ".join(case.get("capabilities", []))
        print(f"{case.get('id')} [{case.get('category')}] {case.get('name')} :: {capabilities}")


def main():
    parser = argparse.ArgumentParser(description="DevAssist RAG/Agent harness runner")
    parser.add_argument("--base-url", default="http://localhost:9900")
    parser.add_argument("--cases", default=str(Path(__file__).parent / "cases"))
    parser.add_argument("--output", default=str(Path(__file__).parent / "reports" / "latest-report.json"))
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--category", help="Run only selected categories, comma-separated. Example: rag,aiops")
    parser.add_argument("--list", action="store_true", help="List selected cases and exit")
    parser.add_argument("--validate-only", action="store_true", help="Validate case schema and exit")
    parser.add_argument("--fail-fast", action="store_true", help="Stop on the first failed case")
    args = parser.parse_args()

    cases_dir = Path(args.cases)
    output = Path(args.output)
    cases = load_cases(cases_dir)

    if not cases:
        print(f"No cases found in {cases_dir}", file=sys.stderr)
        return 2

    validation_errors = validate_cases(cases)
    if validation_errors:
        print("Invalid harness cases:", file=sys.stderr)
        for error in validation_errors:
            print(f"  - {error}", file=sys.stderr)
        return 2

    cases = filter_cases(cases, args.category)
    if not cases:
        print("No cases selected.", file=sys.stderr)
        return 2

    if args.list:
        print_case_list(cases)
        return 0

    if args.validate_only:
        print(f"Validated {len(cases)} case(s) successfully.")
        return 0

    results = []
    for case in cases:
        print(f"[RUN] {case.get('id')} - {case.get('name')}")
        result = run_case(case, args.base_url, args.timeout)
        results.append(result)
        status = "PASS" if result["passed"] else "FAIL"
        print(f"[{status}] {case.get('id')} ({result['durationMs']} ms)")
        for failure in result["failures"]:
            print(f"  - {failure}")
        if args.fail_fast and not result["passed"]:
            print("Fail-fast enabled, stopping.")
            break

    passed_count = sum(1 for result in results if result["passed"])
    failed_count = len(results) - passed_count
    by_category, capabilities, label_summary = summarize_results(results)
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "caseDir": str(cases_dir),
        "selectedCategory": args.category,
        "total": len(results),
        "passed": passed_count,
        "failed": failed_count,
        "byCategory": by_category,
        "capabilityCoverage": capabilities,
        "labelMetrics": label_summary,
        "results": results,
    }
    write_report(report, output)

    print()
    print(f"Total: {len(results)}, Passed: {passed_count}, Failed: {failed_count}")
    print("By category:")
    for category, item in sorted(by_category.items()):
        print(f"  - {category}: {item['passed']}/{item['total']} passed")
    if any(label_summary[key] for key in ["sourceLabelTotal", "rootCauseLabelTotal", "structureLabelTotal"]):
        print("Label metrics:")
        print(f"  - Source Hit Rate: {label_summary['sourceHitRate']}")
        print(f"  - RootCause Hit Rate: {label_summary['rootCauseHitRate']}")
        print(f"  - Structure Hit Rate: {label_summary['structureHitRate']}")
    print(f"Report: {output}")

    return 0 if failed_count == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
