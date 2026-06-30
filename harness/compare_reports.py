#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


def read_json(path: Path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def get_summary(report: dict):
    if "summary" in report:
        summary = report["summary"]
        label_metrics = report.get("labelMetrics", {})
        if not label_metrics:
            label_metrics = summarize_label_metrics(report.get("results", []))
        return {
            "total": summary.get("total", report.get("total", 0)),
            "passRate": summary.get("judgePassRate"),
            "averageOverallScore": summary.get("averageOverallScore"),
            "averageScores": summary.get("averageScores", {}),
            "criticalIssueCount": summary.get("criticalIssueCount"),
            "unsupportedClaimCount": summary.get("unsupportedClaimCount"),
            "judgeParseFailureCount": summary.get("judgeParseFailureCount"),
            "sourceHitRate": label_metrics.get("sourceHitRate"),
            "rootCauseHitRate": label_metrics.get("rootCauseHitRate"),
            "structureHitRate": label_metrics.get("structureHitRate"),
        }
    total = report.get("total", 0)
    passed = report.get("passed", 0)
    label_metrics = report.get("labelMetrics", {})
    if not label_metrics:
        label_metrics = summarize_label_metrics(report.get("results", []))
    return {
        "total": total,
        "passRate": round(passed / total, 4) if total else 0.0,
        "averageOverallScore": None,
        "averageScores": {},
        "criticalIssueCount": None,
        "unsupportedClaimCount": None,
        "judgeParseFailureCount": None,
        "sourceHitRate": label_metrics.get("sourceHitRate"),
        "rootCauseHitRate": label_metrics.get("rootCauseHitRate"),
        "structureHitRate": label_metrics.get("structureHitRate"),
    }


def summarize_label_metrics(results: list):
    source_total = source_hit = 0
    root_total = root_hit = 0
    structure_total = structure_hit = 0
    for result in results:
        metrics = result.get("labelMetrics", {})
        if metrics.get("expectedSources"):
            source_total += 1
            source_hit += 1 if metrics.get("sourceHit") else 0
        if metrics.get("expectedRootCause"):
            root_total += 1
            root_hit += 1 if metrics.get("rootCauseHit") else 0
        if metrics.get("expectedSections"):
            structure_total += 1
            structure_hit += 1 if metrics.get("structureHit") else 0
    return {
        "sourceHitRate": round(source_hit / source_total, 4) if source_total else None,
        "rootCauseHitRate": round(root_hit / root_total, 4) if root_total else None,
        "structureHitRate": round(structure_hit / structure_total, 4) if structure_total else None,
    }


def fmt(value):
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def print_metric(name: str, before, after):
    delta = None
    if isinstance(before, (int, float)) and isinstance(after, (int, float)):
        delta = after - before
    delta_text = f" ({delta:+.4f})" if isinstance(delta, float) else ""
    print(f"{name}: {fmt(before)} -> {fmt(after)}{delta_text}")


def main():
    parser = argparse.ArgumentParser(description="Compare DevAssist harness or judge reports")
    parser.add_argument("--before", required=True)
    parser.add_argument("--after", required=True)
    args = parser.parse_args()

    before = get_summary(read_json(Path(args.before)))
    after = get_summary(read_json(Path(args.after)))

    print("DevAssist Report Comparison")
    print("==========================")
    print_metric("Total Cases", before["total"], after["total"])
    print_metric("Pass Rate", before["passRate"], after["passRate"])
    print_metric("Average Judge Score", before["averageOverallScore"], after["averageOverallScore"])
    print_metric("Critical Issues", before["criticalIssueCount"], after["criticalIssueCount"])
    print_metric("Unsupported Claims", before["unsupportedClaimCount"], after["unsupportedClaimCount"])
    print_metric("Judge Parse Failures", before["judgeParseFailureCount"], after["judgeParseFailureCount"])
    print_metric("Source Hit Rate", before["sourceHitRate"], after["sourceHitRate"])
    print_metric("RootCause Hit Rate", before["rootCauseHitRate"], after["rootCauseHitRate"])
    print_metric("Structure Hit Rate", before["structureHitRate"], after["structureHitRate"])

    score_keys = sorted(set(before["averageScores"].keys()) | set(after["averageScores"].keys()))
    if score_keys:
        print()
        print("Judge Dimension Scores")
        print("----------------------")
        for key in score_keys:
            print_metric(key, before["averageScores"].get(key), after["averageScores"].get(key))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
