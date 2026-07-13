"""Measure deployed /analyze latency and NOVA accuracy against a fixed corpus.

Usage:
    python backend/benchmark_analyze.py https://example.run.app --iterations 1
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Callable


DEFAULT_CORPUS_PATH = Path(__file__).with_name("nova_benchmark_corpus.json")


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((pct / 100) * len(ordered) + 0.5)) - 1))
    return ordered[index]


def load_corpus(path: Path = DEFAULT_CORPUS_PATH) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as corpus_file:
        corpus = json.load(corpus_file)
    if not isinstance(corpus, list):
        raise ValueError("Benchmark corpus must be a JSON array.")
    for index, sample in enumerate(corpus):
        if not isinstance(sample, dict):
            raise ValueError(f"Corpus item {index} must be an object.")
        for field in ("id", "product_name", "ingredient_text", "expected_nova_group"):
            if field not in sample:
                raise ValueError(f"Corpus item {index} missing field: {field}")
        expected = sample["expected_nova_group"]
        if expected not in (1, 2, 3, 4):
            raise ValueError(f"Corpus item {sample['id']} has invalid expected_nova_group: {expected}")
    return corpus


def post_json(url: str, payload: dict[str, Any], timeout: float) -> tuple[int, str]:
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", errors="replace")


def normalize_analyze_url(base_url: str) -> str:
    analyze_url = base_url.rstrip("/")
    if not analyze_url.endswith("/analyze"):
        analyze_url += "/analyze"
    return analyze_url


def nova_group_from_body(body: str) -> int | None:
    try:
        payload = json.loads(body)
    except json.JSONDecodeError:
        return None
    nova = payload.get("nova")
    if not isinstance(nova, dict):
        return None
    group = nova.get("novaGroup")
    return group if isinstance(group, int) else None


def usage_from_body(body: str) -> dict[str, int]:
    try:
        payload = json.loads(body)
    except json.JSONDecodeError:
        return {}
    usage = payload.get("usage")
    if not isinstance(usage, dict):
        return {}
    result: dict[str, int] = {}
    for key in ("inputTokens", "outputTokens", "totalTokens"):
        value = usage.get(key)
        if isinstance(value, int):
            result[key] = value
    if {"inputTokens", "outputTokens", "totalTokens"} <= result.keys():
        result["estimatedHiddenThinkingTokens"] = (
            result["totalTokens"] - result["inputTokens"] - result["outputTokens"]
        )
    return result


def summarize_group(rows: list[dict[str, Any]], expected_group: int) -> dict[str, Any]:
    group_rows = [row for row in rows if row["expectedNovaGroup"] == expected_group]
    latencies = [row["latencyMs"] for row in group_rows if row["httpStatus"] == 200]
    correct = sum(1 for row in group_rows if row.get("correct") is True)
    return {
        "samples": len(group_rows),
        "correct": correct,
        "accuracy": round(correct / len(group_rows), 4) if group_rows else 0,
        "p50Ms": round(statistics.median(latencies), 2) if latencies else 0,
        "p95Ms": round(percentile(latencies, 95), 2) if latencies else 0,
    }


def run_benchmark(
    analyze_url: str,
    corpus: list[dict[str, Any]],
    iterations: int,
    timeout: float,
    post_fn: Callable[[str, dict[str, Any], float], tuple[int, str]] = post_json,
) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    errors: dict[str, int] = {}

    for iteration in range(1, iterations + 1):
        for sample in corpus:
            expected = int(sample["expected_nova_group"])
            payload = {
                "type": "analysis",
                "product_name": sample["product_name"],
                "ingredient_text": sample["ingredient_text"],
            }
            start = time.perf_counter()
            status, body = post_fn(analyze_url, payload, timeout)
            elapsed_ms = (time.perf_counter() - start) * 1000
            actual = nova_group_from_body(body) if 200 <= status < 300 else None
            correct = actual == expected

            if status < 200 or status >= 300:
                key = f"HTTP {status}"
                errors[key] = errors.get(key, 0) + 1
                print(f"{key} {sample['id']}: {body[:300]}", file=sys.stderr)
            elif actual is None:
                errors["missing_nova_group"] = errors.get("missing_nova_group", 0) + 1
            elif not correct:
                errors["nova_mismatch"] = errors.get("nova_mismatch", 0) + 1

            row = {
                "iteration": iteration,
                "id": sample["id"],
                "productName": sample["product_name"],
                "expectedNovaGroup": expected,
                "actualNovaGroup": actual,
                "correct": correct,
                "httpStatus": status,
                "latencyMs": round(elapsed_ms, 2),
            }
            row.update(usage_from_body(body))
            rows.append(row)

    success_rows = [row for row in rows if row["httpStatus"] == 200]
    latencies = [row["latencyMs"] for row in success_rows]
    correct = sum(1 for row in success_rows if row.get("correct") is True)

    return {
        "url": analyze_url,
        "corpusSize": len(corpus),
        "iterations": iterations,
        "totalRequests": len(rows),
        "successfulRequests": len(success_rows),
        "successRate": round(len(success_rows) / len(rows), 4) if rows else 0,
        "novaCorrect": correct,
        "novaAccuracy": round(correct / len(success_rows), 4) if success_rows else 0,
        "p50Ms": round(statistics.median(latencies), 2) if latencies else 0,
        "p95Ms": round(percentile(latencies, 95), 2),
        "p99Ms": round(percentile(latencies, 99), 2),
        "byExpectedNovaGroup": {
            str(group): summarize_group(rows, group) for group in (1, 2, 3, 4)
        },
        "errors": errors,
        "results": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("base_url", help="Cloud Run base URL or full /analyze URL")
    parser.add_argument("--iterations", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS_PATH)
    parser.add_argument("--details", action="store_true", help="Include per-product rows.")
    parser.add_argument("--strict-nova", action="store_true", help="Exit non-zero on NOVA mismatch.")
    args = parser.parse_args()

    corpus = load_corpus(args.corpus)
    result = run_benchmark(
        analyze_url=normalize_analyze_url(args.base_url),
        corpus=corpus,
        iterations=args.iterations,
        timeout=args.timeout,
    )
    if not args.details:
        result = {key: value for key, value in result.items() if key != "results"}
    print(json.dumps(result, indent=2))
    has_http_errors = any(key.startswith("HTTP ") for key in result["errors"])
    has_nova_errors = "nova_mismatch" in result["errors"] or "missing_nova_group" in result["errors"]
    return 1 if has_http_errors or (args.strict_nova and has_nova_errors) else 0


if __name__ == "__main__":
    raise SystemExit(main())
