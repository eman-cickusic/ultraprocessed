"""App Check enforcement tests. No network or real tokens: the verifier is monkeypatched.

Enforcement is gated behind module-level flags on `app_check`, so tests toggle those
attributes (mirroring how test_app.py monkeypatches main.call_gemini) rather than env.
"""

from __future__ import annotations

from fastapi.testclient import TestClient

import app_check
import main

client = TestClient(main.app)

ANALYZE_BODY = {"type": "analysis", "ingredient_text": "water, sugar, palm oil"}
FULL_RESPONSE = {
    "nova": {
        "containsConsumableFoodItem": True,
        "novaGroup": 4,
        "summary": "s",
        "rejectionReason": "",
        "confidence": 0.8,
        "warnings": [],
    },
    "ingredients": {"correctedIngredients": [], "ultraProcessedIngredients": [], "confidence": 0.0, "warnings": []},
    "allergens": {"allergens": [], "confidence": 0.0, "warnings": []},
}


def enable(monkeypatch, project_number="894254677159"):
    monkeypatch.setattr(app_check, "APP_CHECK_ENABLED", True)
    monkeypatch.setattr(app_check, "FIREBASE_PROJECT_NUMBER", project_number)


def stub_model(monkeypatch):
    calls = []
    monkeypatch.setattr(main, "call_full_analysis", lambda j: (calls.append(j), (FULL_RESPONSE, {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0}))[1])
    return calls


def test_disabled_by_default_no_header_needed(monkeypatch):
    # Default posture: enforcement off, so the shipped app (which sends no token) still works.
    assert app_check.APP_CHECK_ENABLED is False
    stub_model(monkeypatch)
    r = client.post("/analyze", json=ANALYZE_BODY)
    assert r.status_code == 200


def test_enabled_missing_header_rejected_before_model(monkeypatch):
    enable(monkeypatch)
    calls = stub_model(monkeypatch)
    r = client.post("/analyze", json=ANALYZE_BODY)
    assert r.status_code == 401
    assert r.json()["detail"]["error"] == "app_check_required"
    assert calls == []  # never reached the paid Vertex call


def test_enabled_invalid_token_rejected_before_model(monkeypatch):
    enable(monkeypatch)
    calls = stub_model(monkeypatch)

    def boom(_token):
        raise ValueError("bad signature")

    monkeypatch.setattr(app_check, "verify_app_check_token", boom)
    r = client.post("/analyze", json=ANALYZE_BODY, headers={"X-Firebase-AppCheck": "garbage"})
    assert r.status_code == 401
    assert r.json()["detail"]["error"] == "app_check_invalid"
    assert calls == []


def test_enabled_valid_token_passes(monkeypatch):
    enable(monkeypatch)
    stub_model(monkeypatch)
    monkeypatch.setattr(app_check, "verify_app_check_token", lambda token: {"sub": "app-id"})
    r = client.post("/analyze", json=ANALYZE_BODY, headers={"X-Firebase-AppCheck": "valid"})
    assert r.status_code == 200
    assert r.json()["type"] == "analysis"


def test_enabled_but_misconfigured_fails_closed(monkeypatch):
    # Flag on but no project number -> deny, never fall open.
    enable(monkeypatch, project_number="")
    calls = stub_model(monkeypatch)
    r = client.post("/analyze", json=ANALYZE_BODY, headers={"X-Firebase-AppCheck": "whatever"})
    assert r.status_code == 500
    assert r.json()["detail"]["error"] == "app_check_misconfigured"
    assert calls == []


def test_chat_is_also_protected(monkeypatch):
    enable(monkeypatch)
    called = []
    monkeypatch.setattr(main, "call_gemini", lambda p: called.append(p))
    r = client.post("/chat", json={"question": "hi", "result": {"productName": "x"}})
    assert r.status_code == 401
    assert called == []


def test_healthz_stays_open_when_enabled(monkeypatch):
    # Cloud Run health checks and uptime probes must not need a token.
    enable(monkeypatch)
    assert client.get("/healthz").status_code == 200
