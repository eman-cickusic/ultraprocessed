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
from pathlib import Path
from typing import Any, Optional

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, ConfigDict, Field

from prompt import (
    build_chat_prompt,
    build_full_analysis_prompt,
)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ultraprocessed-ai-proxy")

# --- Configuration (env vars with spec defaults) ---------------------------------
GCP_PROJECT_ID = os.getenv("GCP_PROJECT_ID", "b2-ultra-processed")
GCP_LOCATION = os.getenv("GCP_LOCATION", "us-east1")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-3.5-flash")
REQUEST_TIMEOUT_MS = int(os.getenv("GEMINI_TIMEOUT_MS", "30000"))
# Gemini thinking tokens share this output budget. Low caps can let thinking
# eat the whole cap on complex labels, truncating the JSON to garbage -> 502 model_response_unparseable.
# Raised to comfortably fit thinking + answer for real OCR inputs. If a pathological label still
# truncates, bound thinking with types.ThinkingConfig(thinking_budget=N) on the generate_content calls.
FULL_ANALYSIS_MAX_OUTPUT_TOKENS = int(os.getenv("GEMINI_MAX_OUTPUT_TOKENS", "4096"))
CHAT_MAX_OUTPUT_TOKENS = int(os.getenv("GEMINI_CHAT_MAX_OUTPUT_TOKENS", "2048"))
MAX_INGREDIENT_CHARS = 20_000
MAX_CHAT_QUESTION_CHARS = 1_000
MAX_CHAT_HISTORY_MESSAGES = 12
MAX_CHAT_HISTORY_CHARS = 4_000
MAX_CHAT_RESULT_ITEMS = 80
MAX_CHAT_RESULT_WARNINGS = 20
ENABLE_DOCS = os.getenv("ENABLE_OPENAPI_DOCS", "false").strip().lower() == "true"
CORS_ALLOWED_ORIGINS = [
    origin.strip()
    for origin in os.getenv("CORS_ALLOWED_ORIGINS", "").split(",")
    if origin.strip()
]
MODULE_VERSION_MANIFEST_PATHS = (
    Path(__file__).resolve().parent.parent / "module_versions.json",
    Path(__file__).with_name("module_versions.json"),
)


def load_module_version_manifest() -> dict[str, Any]:
    manifest_path = next((path for path in MODULE_VERSION_MANIFEST_PATHS if path.is_file()), None)
    if manifest_path is None:
        raise RuntimeError("module version manifest not found")
    with manifest_path.open(encoding="utf-8") as manifest_file:
        manifest = json.load(manifest_file)
    if not isinstance(manifest, dict):
        raise RuntimeError("module version manifest must be a JSON object")
    return manifest


MODULE_VERSION_MANIFEST = load_module_version_manifest()
SERVICE_VERSION = str(MODULE_VERSION_MANIFEST.get("releaseVersion", "0.0.0"))

app = FastAPI(
    title="Ultraprocessed AI Proxy",
    version=SERVICE_VERSION,
    docs_url="/docs" if ENABLE_DOCS else None,
    redoc_url="/redoc" if ENABLE_DOCS else None,
    openapi_url="/openapi.json" if ENABLE_DOCS else None,
)

if CORS_ALLOWED_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=CORS_ALLOWED_ORIGINS,
        allow_credentials=False,
        allow_methods=["POST", "GET"],
        allow_headers=["Content-Type", "Authorization"],
    )


# --- Request / response models ----------------------------------------------------
class AnalyzeRequest(BaseModel):
    # `type` is accepted only for older Android builds that send "analysis".
    type: Optional[str] = None
    ingredient_text: Optional[str] = Field(None, max_length=MAX_INGREDIENT_CHARS)
    barcode: Optional[str] = None
    product_name: Optional[str] = None
    locale: Optional[str] = None

    # Accepted only to return a clear wrong-endpoint error if chat is sent here.
    question: Optional[str] = Field(None, max_length=MAX_CHAT_QUESTION_CHARS)
    result: Optional[dict[str, Any]] = None
    history: Optional[list[dict[str, Any]]] = Field(None, max_length=MAX_CHAT_HISTORY_MESSAGES)


class ChatRequest(BaseModel):
    question: Optional[str] = Field(None, max_length=MAX_CHAT_QUESTION_CHARS)
    result: Optional[dict[str, Any]] = None
    history: Optional[list[dict[str, Any]]] = Field(None, max_length=MAX_CHAT_HISTORY_MESSAGES)


class NovaSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    containsConsumableFoodItem: bool
    novaGroup: int = Field(ge=0, le=4)
    summary: str
    rejectionReason: str
    confidence: float = Field(ge=0.0, le=1.0)
    warnings: list[str]


class UltraProcessedMarkerSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    reason: str


class IngredientsSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    correctedIngredients: list[str]
    ultraProcessedIngredients: list[UltraProcessedMarkerSchema]
    confidence: float = Field(ge=0.0, le=1.0)
    warnings: list[str]


class AllergensSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    allergens: list[str]
    confidence: float = Field(ge=0.0, le=1.0)
    warnings: list[str]


class FullAnalysisSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    nova: NovaSchema
    ingredients: IngredientsSchema
    allergens: AllergensSchema


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
            max_output_tokens=CHAT_MAX_OUTPUT_TOKENS,
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


def response_usage(resp: Any) -> dict:
    usage = {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0}
    meta = getattr(resp, "usage_metadata", None)
    if meta is not None:
        usage = {
            "inputTokens": getattr(meta, "prompt_token_count", 0) or 0,
            "outputTokens": getattr(meta, "candidates_token_count", 0) or 0,
            "totalTokens": getattr(meta, "total_token_count", 0) or 0,
        }
    return usage


def call_full_analysis(input_json: dict[str, Any]) -> tuple[dict, dict]:
    """Run the backend-owned full analysis prompt as one structured Gemini call."""
    from google.genai import types

    client = _get_client()
    resp = client.models.generate_content(
        model=GEMINI_MODEL,
        contents=build_full_analysis_prompt(input_json),
        config=types.GenerateContentConfig(
            temperature=0.0,
            top_p=1.0,
            max_output_tokens=FULL_ANALYSIS_MAX_OUTPUT_TOKENS,
            response_mime_type="application/json",
            response_schema=FullAnalysisSchema,
        ),
    )
    parsed = getattr(resp, "parsed", None)
    if isinstance(parsed, BaseModel):
        return parsed.model_dump(), response_usage(resp)
    if isinstance(parsed, dict):
        return parsed, response_usage(resp)
    if resp.text and resp.text.strip():
        return json.loads(resp.text), response_usage(resp)
    raise ValueError("empty model response")


# --- Parsing helpers --------------------------------------------------------------
_FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)\s*```", re.DOTALL)
_CONTROL_CHAR_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_WHITESPACE_RE = re.compile(r"\s+")


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

    corrected_ingredients = _as_str_list(ingredients.get("correctedIngredients"))
    corrected_names = set(corrected_ingredients)
    markers = []
    for item in ingredients.get("ultraProcessedIngredients") or []:
        if isinstance(item, dict) and item.get("name"):
            name = str(item["name"]).strip()
            if name not in corrected_names:
                continue
            markers.append(
                {"name": name, "reason": str(item.get("reason", "")).strip()}
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
            "correctedIngredients": corrected_ingredients,
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


def clean_input_text(value: Optional[str]) -> str:
    cleaned = _CONTROL_CHAR_RE.sub(" ", value or "")
    return _WHITESPACE_RE.sub(" ", cleaned).strip()


def bounded_text(value: Any, max_chars: int) -> str:
    return clean_input_text(str(value) if value is not None else "")[:max_chars]


def bounded_str_list(value: Any, max_items: int, max_chars: int) -> list[str]:
    if not isinstance(value, list):
        return []
    return [
        item
        for item in (bounded_text(raw, max_chars) for raw in value[:max_items])
        if item
    ]


def split_ingredients(text: str) -> list[str]:
    return [part.strip() for part in re.split(r"[,;\n•]+", text) if part.strip()]


def build_full_analysis_input(req: AnalyzeRequest) -> dict[str, Any]:
    raw_text = clean_input_text(req.ingredient_text)
    payload: dict[str, Any] = {
        "rawIngredientText": raw_text,
        "ingredients": split_ingredients(raw_text),
    }
    product_name = clean_input_text(req.product_name)
    barcode = clean_input_text(req.barcode)
    locale = clean_input_text(req.locale)
    if product_name:
        payload["productName"] = product_name
    if barcode:
        payload["barcode"] = barcode
    if locale:
        payload["locale"] = locale
    return payload


def analyze_food_label(req: AnalyzeRequest) -> dict:
    if not clean_input_text(req.ingredient_text):
        raise HTTPException(
            status_code=422,
            detail={"error": "ingredient_text_required", "message": "ingredient_text is required."},
        )

    response, usage = call_full_analysis(build_full_analysis_input(req))
    return coerce_response(response, GEMINI_MODEL, usage) | {"type": "analysis"}


def trim_chat_history(history: Optional[list[dict[str, Any]]]) -> list[dict[str, str]]:
    normalized: list[dict[str, str]] = []
    for item in history or []:
        role = str(item.get("role", "")).strip().lower()
        if role not in {"user", "assistant"}:
            continue
        text = str(item.get("text", "")).replace("\x00", "").strip()
        if text:
            normalized.append({"role": role, "text": text[:1000]})

    trimmed: list[dict[str, str]] = []
    total_chars = 0
    for item in reversed(normalized[-MAX_CHAT_HISTORY_MESSAGES:]):
        item_chars = len(item["text"])
        if total_chars + item_chars > MAX_CHAT_HISTORY_CHARS:
            remaining = MAX_CHAT_HISTORY_CHARS - total_chars
            if remaining <= 0:
                break
            item = {"role": item["role"], "text": item["text"][-remaining:]}
            item_chars = len(item["text"])
        trimmed.append(item)
        total_chars += item_chars
    return list(reversed(trimmed))


def sanitized_chat_result(result: dict[str, Any]) -> dict[str, Any]:
    """Keep only scan-result fields the chat prompt needs, with bounded text."""
    assessments = []
    for item in result.get("ingredientAssessments") or []:
        if not isinstance(item, dict):
            continue
        name = bounded_text(item.get("name"), 120)
        if not name:
            continue
        assessments.append(
            {
                "name": name,
                "verdict": bounded_text(item.get("verdict"), 40),
                "reason": bounded_text(item.get("reason"), 240),
            }
        )
        if len(assessments) >= MAX_CHAT_RESULT_ITEMS:
            break

    try:
        nova_group = int(result.get("novaGroup", 0))
    except (TypeError, ValueError):
        nova_group = 0

    return {
        "productName": bounded_text(result.get("productName"), 160),
        "novaGroup": min(4, max(0, nova_group)),
        "summary": bounded_text(result.get("summary"), 500),
        "sourceLabel": bounded_text(result.get("sourceLabel"), 80),
        "confidence": _as_float(result.get("confidence")),
        "ingredients": bounded_str_list(result.get("ingredients"), MAX_CHAT_RESULT_ITEMS, 120),
        "ingredientAssessments": assessments,
        "allergens": bounded_str_list(result.get("allergens"), 20, 80),
        "warnings": bounded_str_list(result.get("warnings"), MAX_CHAT_RESULT_WARNINGS, 200),
    }


def looks_like_injection_attempt(*texts: str) -> bool:
    joined = "\n".join(texts).lower()
    return any(marker in joined for marker in INJECTION_MARKERS)


def refusal_chat_response(reason: str) -> dict:
    return {
        "type": "chat",
        "reply": {
            "allowed": False,
            "answer": "I can only answer questions about this scan result.",
            "reason": reason,
        },
        "model": GEMINI_MODEL,
        "usage": {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0},
    }


def coerce_chat_reply(data: dict, usage: dict) -> dict:
    allowed = bool(data.get("allowed", False))
    answer = str(data.get("answer", "")).strip() or (
        "I received the scan context, but the assistant did not return a usable sentence."
    )
    reason = str(data.get("reason", "")).strip()
    return {
        "type": "chat",
        "reply": {"allowed": allowed, "answer": answer, "reason": reason},
        "model": GEMINI_MODEL,
        "usage": usage,
    }


def analyze_chat(req: ChatRequest) -> dict:
    question = (req.question or "").strip()
    if not question:
        raise HTTPException(
            status_code=422,
            detail={"error": "question_required", "message": "question is required."},
        )
    if not isinstance(req.result, dict):
        raise HTTPException(
            status_code=422,
            detail={"error": "result_required", "message": "result is required."},
        )

    history = trim_chat_history(req.history)
    history_text = "\n".join(item["text"] for item in history)
    if looks_like_injection_attempt(question, history_text):
        return refusal_chat_response("Prompt injection attempt detected.")

    raw_text, usage = call_gemini(
        build_chat_prompt(sanitized_chat_result(req.result), question, history)
    )
    return coerce_chat_reply(parse_model_json(raw_text), usage)


INJECTION_MARKERS = (
    "ignore previous",
    "ignore the above",
    "system prompt",
    "developer message",
    "jailbreak",
    "act as",
    "pretend to be",
    "reveal",
    "bypass",
    "prompt injection",
)


# --- Endpoints --------------------------------------------------------------------
@app.get("/healthz")
def healthz() -> dict:
    return {"status": "ok"}


@app.get("/version")
def version() -> dict:
    return MODULE_VERSION_MANIFEST


@app.post("/analyze")
def analyze(req: AnalyzeRequest) -> dict:
    try:
        operation = (req.type or "").strip().lower()
        if operation == "chat" or req.question is not None or req.result is not None or req.history is not None:
            raise HTTPException(
                status_code=422,
                detail={"error": "wrong_endpoint", "message": "Use /chat for result chat."},
            )
        if operation and operation != "analysis":
            raise HTTPException(
                status_code=422,
                detail={"error": "unsupported_type", "message": "type must be analysis when provided."},
            )
        return analyze_food_label(req)
    except ValueError as exc:
        logger.warning("Model returned unparseable output: %s", exc)
        raise HTTPException(
            status_code=502,
            detail={
                "error": "model_response_unparseable",
                "message": "AI model returned an invalid response.",
            },
        )
    except Exception as exc:  # network, auth, timeout, model errors
        if isinstance(exc, HTTPException):
            raise exc
        logger.exception("Gemini call failed")
        raise HTTPException(
            status_code=502,
            detail={
                "error": "model_call_failed",
                "message": "AI model call failed.",
            },
        )


@app.post("/chat")
def chat(req: ChatRequest) -> dict:
    try:
        return analyze_chat(req)
    except ValueError as exc:
        logger.warning("Model returned unparseable output: %s", exc)
        raise HTTPException(
            status_code=502,
            detail={
                "error": "model_response_unparseable",
                "message": "AI model returned an invalid response.",
            },
        )
    except Exception as exc:  # network, auth, timeout, model errors
        if isinstance(exc, HTTPException):
            raise exc
        logger.exception("Gemini call failed")
        raise HTTPException(
            status_code=502,
            detail={
                "error": "model_call_failed",
                "message": "AI model call failed.",
            },
        )
