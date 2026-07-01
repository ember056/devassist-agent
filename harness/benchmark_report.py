#!/usr/bin/env python3
import argparse
import json
from datetime import datetime
from pathlib import Path


def read_json(path: Path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def fmt(value):
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def load_judge_by_id(path: Path | None):
    if not path:
        return {}
    data = read_json(path)
    return {
        item.get("id"): item.get("judgeResult", {})
        for item in data.get("results", [])
    }


def write_markdown(benchmark: dict, judge_by_id: dict, output: Path):
    output.parent.mkdir(parents=True, exist_ok=True)
    label_metrics = benchmark.get("labelMetrics", {})
    total = benchmark.get("total", 0)
    passed = benchmark.get("passed", 0)
    pass_rate = round(passed / total, 4) if total else 0.0

    judge_results = [item for item in judge_by_id.values() if item.get("judgeStatus") == "SUCCESS"]
    judge_passed = sum(1 for item in judge_results if item.get("passed"))
    judge_pass_rate = round(judge_passed / len(judge_results), 4) if judge_results else None
    avg_judge = (
        round(sum(float(item.get("overallScore", 0.0)) for item in judge_results) / len(judge_results), 4)
        if judge_results else None
    )

    lines = [
        "# DevAssist Benchmark Report",
        "",
        f"Generated at: `{datetime.now().isoformat(timespec='seconds')}`",
        "",
        "## Summary",
        "",
        "| Metric | Value |",
        "|---|---:|",
        f"| Total cases | {total} |",
        f"| Harness pass rate | {fmt(pass_rate)} |",
        f"| Source Hit Rate | {fmt(label_metrics.get('sourceHitRate'))} |",
        f"| RootCause Hit Rate | {fmt(label_metrics.get('rootCauseHitRate'))} |",
        f"| Structure Hit Rate | {fmt(label_metrics.get('structureHitRate'))} |",
        f"| Judge pass rate | {fmt(judge_pass_rate)} |",
        f"| Average Judge score | {fmt(avg_judge)} |",
        "",
        "## Cases",
        "",
        "| Case | Category | Harness | Latency(ms) | Source Hit | RootCause Hit | Structure Hit | Judge | Score |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|",
    ]

    for item in benchmark.get("results", []):
        metrics = item.get("labelMetrics", {})
        judge = judge_by_id.get(item.get("id"), {})
        lines.append(
            "| {id} | {category} | {passed} | {latency} | {source} | {root} | {structure} | {judge_passed} | {score} |".format(
                id=item.get("id"),
                category=item.get("category"),
                passed="PASS" if item.get("passed") else "FAIL",
                latency=item.get("durationMs"),
                source="PASS" if metrics.get("sourceHit") else "FAIL",
                root="PASS" if metrics.get("rootCauseHit") else "FAIL",
                structure="PASS" if metrics.get("structureHit") else "FAIL",
                judge_passed="PASS" if judge.get("passed") else ("FAIL" if judge else "n/a"),
                score=fmt(judge.get("overallScore") if judge else None),
            )
        )

    low_cases = [
        (case_id, judge)
        for case_id, judge in judge_by_id.items()
        if judge.get("judgeStatus") == "SUCCESS" and not judge.get("passed")
    ]
    if low_cases:
        lines.extend(["", "## Low Score Cases", ""])
        for case_id, judge in low_cases:
            lines.append(f"### {case_id}")
            lines.append("")
            lines.append(f"- Score: `{fmt(judge.get('overallScore'))}`")
            lines.append(f"- Reason: {judge.get('reason', '')}")
            critical = judge.get("criticalIssues") or []
            if critical:
                lines.append("- Critical issues:")
                for issue in critical:
                    lines.append(f"  - {issue}")
            unsupported = judge.get("unsupportedClaims") or []
            if unsupported:
                lines.append("- Unsupported claims:")
                for claim in unsupported:
                    lines.append(f"  - {claim}")
            lines.append("")

    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Generate a Markdown benchmark report")
    parser.add_argument("--benchmark", required=True, help="Harness benchmark JSON report")
    parser.add_argument("--judge", help="Optional Judge JSON report")
    parser.add_argument("--output", default="harness/benchmark/reports/latest-report.md")
    args = parser.parse_args()

    benchmark = read_json(Path(args.benchmark))
    judge_by_id = load_judge_by_id(Path(args.judge)) if args.judge else {}
    write_markdown(benchmark, judge_by_id, Path(args.output))
    print(f"Markdown report: {args.output}")


if __name__ == "__main__":
    raise SystemExit(main())
