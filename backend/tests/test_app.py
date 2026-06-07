"""Tests run without live Vertex credentials: call_gemini is monkeypatched."""

import json

from fastapi.testclient import TestClient

import main

client = TestClient(main.app)

CANNED = {
    "nova": {
        "containsConsumableFoodItem": True,
        "novaGroup": 4,
        "summary": "Ultra-processed snack with additives.",
        "rejectionReason": "",
        "confidence": 0.85,
        "warnings": [],
    },
    "ingredients": {
        "correctedIngredients": ["water", "sugar", "palm oil", "emulsifier"],
        "ultraProcessedIngredients": [
            {"name": "emulsifier", "reason": "Common ultra-processing marker."}
        ],
        "confidence": 0.8,
        "warnings": [],
    },
    "allergens": {"allergens": ["milk"], "confidence": 0.6, "warnings": []},
}

USAGE = {"inputTokens": 12, "outputTokens": 34, "totalTokens": 46}


def test_healthz():
    r = client.get("/healthz")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_analyze_mocked(monkeypatch):
    monkeypatch.setattr(main, "call_gemini", lambda p: (json.dumps(CANNED), USAGE))
    r = client.post("/analyze", json={"ingredient_text": "water, sugar, palm oil, emulsifier"})
    assert r.status_code == 200
    body = r.json()
    assert body["nova"]["novaGroup"] == 4
    assert body["nova"]["containsConsumableFoodItem"] is True
    assert body["ingredients"]["ultraProcessedIngredients"][0]["name"] == "emulsifier"
    assert body["allergens"]["allergens"] == ["milk"]
    assert body["model"] == main.GEMINI_MODEL
    assert body["usage"] == USAGE


def test_analyze_strips_markdown_fence(monkeypatch):
    fenced = "```json\n" + json.dumps(CANNED) + "\n```"
    monkeypatch.setattr(main, "call_gemini", lambda p: (fenced, USAGE))
    r = client.post("/analyze", json={"ingredient_text": "water, sugar"})
    assert r.status_code == 200
    assert r.json()["nova"]["novaGroup"] == 4


def test_analyze_clamps_nova_group(monkeypatch):
    bad = json.loads(json.dumps(CANNED))
    bad["nova"]["novaGroup"] = 9  # out of range
    monkeypatch.setattr(main, "call_gemini", lambda p: (json.dumps(bad), USAGE))
    r = client.post("/analyze", json={"ingredient_text": "x"})
    assert r.json()["nova"]["novaGroup"] == 4


def test_analyze_rejects_overlong_text():
    r = client.post("/analyze", json={"ingredient_text": "x" * 20_001})
    assert r.status_code == 422


def test_analyze_rejects_empty_text():
    r = client.post("/analyze", json={"ingredient_text": ""})
    assert r.status_code == 422


def test_analyze_model_failure_returns_502(monkeypatch):
    def boom(_):
        raise RuntimeError("vertex unavailable")

    monkeypatch.setattr(main, "call_gemini", boom)
    r = client.post("/analyze", json={"ingredient_text": "water"})
    assert r.status_code == 502
    assert r.json()["detail"]["error"] == "model_call_failed"


def test_analyze_unparseable_returns_502(monkeypatch):
    monkeypatch.setattr(main, "call_gemini", lambda p: ("not json at all", USAGE))
    r = client.post("/analyze", json={"ingredient_text": "water"})
    assert r.status_code == 502
    assert r.json()["detail"]["error"] == "model_response_unparseable"
