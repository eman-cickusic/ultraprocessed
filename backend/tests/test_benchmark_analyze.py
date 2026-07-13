from __future__ import annotations

import json
from collections import Counter

import benchmark_analyze


def test_nova_benchmark_corpus_has_ten_products_per_group():
    corpus = benchmark_analyze.load_corpus()

    assert len(corpus) == 40
    counts = Counter(sample["expected_nova_group"] for sample in corpus)
    assert counts == {1: 10, 2: 10, 3: 10, 4: 10}
    assert len({sample["id"] for sample in corpus}) == 40
    assert all(sample["product_name"].strip() for sample in corpus)
    assert all(sample["ingredient_text"].strip() for sample in corpus)


def test_benchmark_reports_latency_and_nova_accuracy():
    corpus = [
        {
            "id": "nova1-oats",
            "product_name": "Oats",
            "ingredient_text": "whole grain oats",
            "expected_nova_group": 1,
        },
        {
            "id": "nova4-cereal",
            "product_name": "Cereal",
            "ingredient_text": "corn syrup, soy lecithin, natural flavor",
            "expected_nova_group": 4,
        },
    ]
    responses = {
        "Oats": {"nova": {"novaGroup": 1}, "usage": {"inputTokens": 10, "outputTokens": 4, "totalTokens": 20}},
        "Cereal": {"nova": {"novaGroup": 3}, "usage": {"inputTokens": 12, "outputTokens": 5, "totalTokens": 25}},
    }

    def fake_post(_url, payload, _timeout):
        return 200, json.dumps(responses[payload["product_name"]])

    result = benchmark_analyze.run_benchmark(
        analyze_url="https://proxy.test/analyze",
        corpus=corpus,
        iterations=1,
        timeout=1,
        post_fn=fake_post,
    )

    assert result["totalRequests"] == 2
    assert result["successfulRequests"] == 2
    assert result["novaCorrect"] == 1
    assert result["novaAccuracy"] == 0.5
    assert result["errors"] == {"nova_mismatch": 1}
    assert result["byExpectedNovaGroup"]["1"]["correct"] == 1
    assert result["byExpectedNovaGroup"]["4"]["correct"] == 0
    assert result["results"][0]["estimatedHiddenThinkingTokens"] == 6
