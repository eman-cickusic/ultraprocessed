"""Prompt construction for the NOVA / ingredient / allergen analysis call.

The response schema intentionally mirrors the Android app's existing Kotlin
contract (``NovaClassification`` + ``IngredientListAnalysis`` + ``AllergenDetection``
in ``app/src/main/java/com/b2/ultraprocessed/network/llm``) so a future client can
deserialize the proxy response directly.
"""

from __future__ import annotations

import json
from typing import Optional

# Shape the model is told to return. Keys match the app data classes exactly.
RESPONSE_SHAPE = {
    "nova": {
        "containsConsumableFoodItem": "boolean",
        "novaGroup": "integer 1-4, or 0 if not a consumable food",
        "summary": "2-3 line plain-language note for a shopper",
        "rejectionReason": "string, why rejected if not consumable, else empty",
        "confidence": "number 0.0-1.0",
        "warnings": ["string"],
    },
    "ingredients": {
        "correctedIngredients": ["cleaned ingredient name"],
        "ultraProcessedIngredients": [
            {"name": "ingredient", "reason": "why it is an ultra-processing marker"}
        ],
        "confidence": "number 0.0-1.0",
        "warnings": ["string"],
    },
    "allergens": {
        "allergens": ["common allergen name"],
        "confidence": "number 0.0-1.0",
        "warnings": ["string"],
    },
}

SYSTEM_RULES = """You are a food-label analysis service. You classify a packaged
food product using ONLY the ingredient text (and optional product name / barcode)
provided. You apply the NOVA food-processing framework (1 = unprocessed/minimally
processed, 2 = processed culinary ingredients, 3 = processed foods, 4 = ultra-processed).

Hard rules:
- Return STRICT JSON only. No markdown, no code fences, no prose outside the JSON.
- Do NOT provide medical diagnosis or health advice. Describe processing, not disease.
- Do NOT invent ingredients that are not present in the input text.
- If the text is unclear, partial, or not a food label, set low confidence, add a
  short note in "warnings", and (if not a consumable food) set
  containsConsumableFoodItem=false with a rejectionReason.
- Allergens that are not explicitly stated must be treated as uncertain: include them
  only when reasonably implied and keep confidence modest. Never assert a confirmed
  allergen from ambiguous text.
- "correctedIngredients" should be the cleaned, de-OCR'd ingredient names in order.
- "ultraProcessedIngredients" lists only ingredients that are genuine NOVA-4 markers
  (e.g. emulsifiers, flavour enhancers, colours, hydrogenated oils, sweeteners)."""


def build_prompt(
    ingredient_text: str,
    product_name: Optional[str] = None,
    barcode: Optional[str] = None,
    locale: Optional[str] = None,
) -> str:
    """Assemble the full prompt string sent to Gemini."""
    payload = {
        "productName": product_name or "",
        "barcode": barcode or "",
        "locale": locale or "",
        "ingredientText": ingredient_text,
    }
    return (
        f"{SYSTEM_RULES}\n\n"
        "## Required JSON output shape (return exactly these keys)\n"
        f"{json.dumps(RESPONSE_SHAPE, indent=2)}\n\n"
        "## Input\n"
        f"{json.dumps(payload, ensure_ascii=False)}\n\n"
        "Return only the JSON object."
    )
