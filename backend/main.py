"""FastAPI backend proxy for the Ultraprocessed (Zest) Android app.

Receives OCR-extracted ingredient text and returns a NOVA classification,
ingredient analysis, ultra-processing markers, and likely allergens by calling
Gemini through **Vertex AI** using the Cloud Run service-account identity
(Application Default Credentials). No user API keys, no service-account JSON files.

Response shape mirrors the app's existing Kotlin contract (see prompt.py).
"""

from __future__ import annotations

import json
import logging
import os
import re
from typing import Any, Optional

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from prompt import build_prompt

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ultraprocessed-ai-proxy")

# --- Configuration (env vars with spec defaults) ---------------------------------
GCP_PROJECT_ID = os.getenv("GCP_PROJECT_ID", "b2-ultra-processed")
GCP_LOCATION = os.getenv("GCP_LOCATION", "us-east1")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")
REQUEST_TIMEOUT_MS = int(os.getenv("GEMINI_TIMEOUT_MS", "30000"))
MAX_INGREDIENT_CHARS = 20_000

app = FastAPI(title="Ultraprocessed AI Proxy", version="1.0.0")

# CORS: permissive for development. Production should restrict origins (see README).
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# --- Request / response models ----------------------------------------------------
class AnalyzeRequest(BaseModel):
    ingredient_text: str = Field(..., min_length=1, max_length=MAX_INGREDIENT_CHARS)
    barcode: Optional[str] = None
    product_name: Optional[str] = None
    locale: Optional[str] = None


# --- Vertex / Gemini client (lazy so import works without creds, e.g. in tests) ---
_client = None


def _get_client():
    """Create and cache the Vertex-mode google-genai client (ADC auth)."""
    global _client
    if _client is None:
        from google import genai
        from google.genai import types

        _client = genai.Client(
            vertexai=True,
            project=GCP_PROJECT_ID,
            location=GCP_LOCATION,
            http_options=types.HttpOptions(timeout=REQUEST_TIMEOUT_MS),
        )
    return _client


def call_gemini(prompt_text: str) -> tuple[str, dict]:
    """Call Gemini via Vertex AI. Returns (raw_text, usage_dict).

    Monkeypatched in tests so the suite runs without live credentials.
    """
    from google.genai import types

    client = _get_client()
    resp = client.models.generate_content(
        model=GEMINI_MODEL,
        contents=prompt_text,
        config=types.GenerateContentConfig(
            temperature=0.0,
            top_p=1.0,
            max_output_tokens=2048,
            response_mime_type="application/json",
        ),
    )
    usage = {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0}
    meta = getattr(resp, "usage_metadata", None)
    if meta is not None:
        usage = {
            "inputTokens": getattr(meta, "prompt_token_count", 0) or 0,
            "outputTokens": getattr(meta, "candidates_token_count", 0) or 0,
            "totalTokens": getattr(meta, "total_token_count", 0) or 0,
        }
    return (resp.text or "", usage)


# --- Parsing helpers --------------------------------------------------------------
_FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)\s*```", re.DOTALL)


def parse_model_json(text: str) -> dict:
    """Parse the model output into a dict, tolerating fences / surrounding prose."""
    if not text or not text.strip():
        raise ValueError("empty model response")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    # Strip a ```json ... ``` fence if present.
    fence = _FENCE_RE.search(text)
    if fence:
        try:
            return json.loads(fence.group(1))
        except json.JSONDecodeError:
            pass
    # Last resort: grab the outermost {...} span.
    start, end = text.find("{"), text.rfind("}")
    if start != -1 and end != -1 and end > start:
        return json.loads(text[start : end + 1])
    raise ValueError("could not parse JSON from model response")


def _as_float(value: Any, default: float = 0.5) -> float:
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError):
        return default


def _as_str_list(value: Any) -> list[str]:
    if isinstance(value, list):
        return [str(v).strip() for v in value if str(v).strip()]
    return []


def coerce_response(data: dict, model: str, usage: dict) -> dict:
    """Coerce raw model JSON into the app-aligned response, with safe defaults."""
    nova = data.get("nova") or {}
    ingredients = data.get("ingredients") or {}
    allergens = data.get("allergens") or {}

    try:
        nova_group = int(nova.get("novaGroup", 0))
    except (TypeError, ValueError):
        nova_group = 0
    consumable = bool(nova.get("containsConsumableFoodItem", nova_group != 0))
    if consumable:
        nova_group = min(4, max(1, nova_group))
    else:
        nova_group = 0

    markers = []
    for item in ingredients.get("ultraProcessedIngredients") or []:
        if isinstance(item, dict) and item.get("name"):
            markers.append(
                {"name": str(item["name"]).strip(), "reason": str(item.get("reason", "")).strip()}
            )

    return {
        "nova": {
            "containsConsumableFoodItem": consumable,
            "novaGroup": nova_group,
            "summary": str(nova.get("summary", "")).strip(),
            "rejectionReason": str(nova.get("rejectionReason", "")).strip(),
            "confidence": _as_float(nova.get("confidence")),
            "warnings": _as_str_list(nova.get("warnings")),
        },
        "ingredients": {
            "correctedIngredients": _as_str_list(ingredients.get("correctedIngredients")),
            "ultraProcessedIngredients": markers,
            "confidence": _as_float(ingredients.get("confidence")),
            "warnings": _as_str_list(ingredients.get("warnings")),
        },
        "allergens": {
            "allergens": _as_str_list(allergens.get("allergens")),
            "confidence": _as_float(allergens.get("confidence")),
            "warnings": _as_str_list(allergens.get("warnings")),
        },
        "model": model,
        "usage": usage,
    }


# --- Endpoints --------------------------------------------------------------------
@app.get("/healthz")
def healthz() -> dict:
    return {"status": "ok"}


@app.post("/analyze")
def analyze(req: AnalyzeRequest) -> dict:
    prompt_text = build_prompt(
        ingredient_text=req.ingredient_text,
        product_name=req.product_name,
        barcode=req.barcode,
        locale=req.locale,
    )
    try:
        raw_text, usage = call_gemini(prompt_text)
    except Exception as exc:  # network, auth, timeout, model errors
        logger.exception("Gemini call failed")
        raise HTTPException(
            status_code=502,
            detail={"error": "model_call_failed", "message": str(exc)},
        )

    try:
        parsed = parse_model_json(raw_text)
    except ValueError as exc:
        logger.warning("Model returned unparseable output: %s", exc)
        raise HTTPException(
            status_code=502,
            detail={
                "error": "model_response_unparseable",
                "message": str(exc),
            },
        )

    return coerce_response(parsed, GEMINI_MODEL, usage)
