# Ultraprocessed AI Proxy (Cloud Run + Vertex AI)

A small FastAPI service that lets the Ultraprocessed (Zest) Android app stop shipping
user-supplied AI keys. The app sends **OCR-extracted ingredient text** to this proxy; the
proxy calls **Gemini through Vertex AI** using the Cloud Run **service-account identity**
(Application Default Credentials) and returns a NOVA classification, ingredient analysis,
ultra-processing markers, and likely allergens.

- No user API keys.
- No service-account JSON files — auth is the Cloud Run runtime identity (ADC).
- Images never reach this service; only text, matching the app's privacy model.

## Endpoints

### `GET /healthz`
```json
{ "status": "ok" }
```

### `POST /analyze`
Request:
```json
{
  "ingredient_text": "water, sugar, palm oil, emulsifier (soy lecithin)",
  "barcode": "optional string",
  "product_name": "optional string",
  "locale": "optional string"
}
```
`ingredient_text` is required and capped at 20,000 characters (longer → HTTP 422).

Response — the shape mirrors the app's existing Kotlin contract
(`NovaClassification` + `IngredientListAnalysis` + `AllergenDetection`), so future client
integration is a direct deserialize:
```json
{
  "nova": {
    "containsConsumableFoodItem": true,
    "novaGroup": 4,
    "summary": "Short plain-language explanation.",
    "rejectionReason": "",
    "confidence": 0.85,
    "warnings": []
  },
  "ingredients": {
    "correctedIngredients": ["water", "sugar", "palm oil", "emulsifier"],
    "ultraProcessedIngredients": [
      { "name": "emulsifier", "reason": "Common ultra-processing marker." }
    ],
    "confidence": 0.8,
    "warnings": []
  },
  "allergens": {
    "allergens": ["soy"],
    "confidence": 0.6,
    "warnings": []
  },
  "model": "gemini-2.5-flash",
  "usage": { "inputTokens": 0, "outputTokens": 0, "totalTokens": 0 }
}
```
On model/auth/timeout failure the service returns HTTP 502 with
`{"detail": {"error": "...", "message": "..."}}`.

## Environment variables

| Variable          | Default            | Purpose                                  |
|-------------------|--------------------|------------------------------------------|
| `GCP_PROJECT_ID`  | `b2-ultra-processed` | GCP project for Vertex AI               |
| `GCP_LOCATION`    | `us-east1`         | Vertex AI region                         |
| `GEMINI_MODEL`    | `gemini-2.5-flash` | Model id                                 |
| `GEMINI_TIMEOUT_MS` | `30000`          | Per-request model timeout (ms)           |
| `PORT`            | `8080`             | Server port (set by Cloud Run)           |

## Local development

```bash
cd backend
python -m venv .venv && . .venv/bin/activate     # Windows: .venv\Scripts\Activate.ps1
pip install -r requirements-dev.txt

# Run tests (no GCP credentials needed — the model call is mocked):
pytest

# Run the server:
uvicorn main:app --reload --port 8080
```

For a **live** `/analyze` call locally you need Application Default Credentials with
Vertex AI access:
```bash
gcloud auth application-default login
gcloud config set project b2-ultra-processed
```
Without ADC, `/healthz` works and `/analyze` returns a 502 model-call error — expected.

### Example requests
```bash
curl http://localhost:8080/healthz

curl -X POST http://localhost:8080/analyze \
  -H "Content-Type: application/json" \
  -d '{"ingredient_text":"water, sugar, palm oil, emulsifier (soy lecithin)","product_name":"Choco Wafer"}'
```

## Deploy to Cloud Run

```bash
gcloud run deploy ultraprocessed-ai-proxy \
  --source backend \
  --project b2-ultra-processed \
  --region us-east1 \
  --service-account up-app-service@b2-ultra-processed.iam.gserviceaccount.com \
  --allow-unauthenticated \
  --set-env-vars GCP_PROJECT_ID=b2-ultra-processed,GCP_LOCATION=us-east1,GEMINI_MODEL=gemini-2.5-flash
```

The service account must have Vertex AI access (e.g. `roles/aiplatform.user`) on the project.

## Security notes

- **`--allow-unauthenticated` is for initial testing only.** Before production, protect the
  endpoint with app-level auth — Firebase Auth / App Check, API Gateway, or another approved
  mechanism — and remove public access.
- **CORS is wide open (`*`) for development.** Restrict `allow_origins` to known origins for
  production in `main.py`.
- No secrets, API keys, or service-account JSON are committed or required at runtime; auth is
  the Cloud Run service-account identity via ADC.

## Notes / caveats

- **Region + model:** Vertex AI regional availability for `gemini-2.5-flash` varies. If a deploy
  reports the model is unavailable in `us-east1`, set `GCP_LOCATION=global` (or `us-central1`)
  via `--set-env-vars` and redeploy.
- **Single combined call:** this proxy makes one Gemini call returning all three sections. The
  app currently performs three staged LLM calls (NOVA, ingredient cleanup, allergens). Wiring the
  app to this proxy is intended as follow-up work once the Cloud Run URL exists.
