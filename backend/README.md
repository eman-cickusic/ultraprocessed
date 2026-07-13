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

Analysis request:
```json
{
  "type": "analysis",
  "ingredient_text": "water, sugar, palm oil, emulsifier (soy lecithin)",
  "barcode": "optional string",
  "product_name": "optional string",
  "locale": "optional string"
}
```
`type` may be omitted for backwards-compatible analysis calls. `ingredient_text` is required for analysis and capped at 20,000 characters (longer → HTTP 422).

Analysis response — the shape mirrors the app's existing Kotlin contract
(`NovaClassification` + `IngredientListAnalysis` + `AllergenDetection`), so future client
integration is a direct deserialize:
```json
{
  "type": "analysis",
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
  "model": "gemini-3.5-flash",
  "usage": { "inputTokens": 0, "outputTokens": 0, "totalTokens": 0 }
}
```

### `POST /chat`

Chat request:
```json
{
  "question": "Which ingredient is the biggest concern?",
  "result": {
    "productName": "Choco Wafer",
    "novaGroup": 4,
    "summary": "Short plain-language explanation.",
    "sourceLabel": "OCR",
    "confidence": 0.85,
    "ingredients": ["water", "sugar", "palm oil", "emulsifier"],
    "ingredientAssessments": [
      { "name": "emulsifier", "verdict": "nova-4", "reason": "Common marker." }
    ],
    "allergens": ["soy"],
    "warnings": []
  },
  "history": [
    { "role": "user", "text": "Why is this NOVA 4?" },
    { "role": "assistant", "text": "Because the scan shows additive markers." }
  ]
}
```

Chat response:
```json
{
  "type": "chat",
  "reply": {
    "allowed": true,
    "answer": "Short scan-scoped answer.",
    "reason": ""
  },
  "model": "gemini-3.5-flash",
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
| `GEMINI_MODEL`    | `gemini-3.5-flash` | Model id                                 |
| `GEMINI_TIMEOUT_MS` | `30000`          | Per-request model timeout (ms)           |
| `GEMINI_MAX_OUTPUT_TOKENS` | `4096`    | Analysis output cap (shared by thinking + JSON answer) |
| `GEMINI_CHAT_MAX_OUTPUT_TOKENS` | `2048` | Result-chat output cap (shared by thinking + answer) |
| `ENABLE_OPENAPI_DOCS` | `false`        | Enables `/docs`, `/redoc`, and `/openapi.json` only for local/dev use |
| `CORS_ALLOWED_ORIGINS` | empty         | Comma-separated browser origins allowed to call the API; empty disables CORS middleware |
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

For a **live** `/analyze` or `/chat` call locally you need Application Default Credentials with
Vertex AI access:
```bash
gcloud auth application-default login
gcloud config set project b2-ultra-processed
```
Without ADC, `/healthz` works and `/analyze` or `/chat` returns a 502 model-call error — expected.

### Example requests
```bash
curl http://localhost:8080/healthz

curl -X POST http://localhost:8080/analyze \
  -H "Content-Type: application/json" \
  -d '{"ingredient_text":"water, sugar, palm oil, emulsifier (soy lecithin)","product_name":"Choco Wafer"}'

curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"question":"Which ingredient is the biggest concern?","result":{"productName":"Choco Wafer","novaGroup":4,"summary":"Contains additive markers.","sourceLabel":"OCR","confidence":0.85,"ingredients":["sugar","soy lecithin"],"ingredientAssessments":[{"name":"soy lecithin","verdict":"nova-4","reason":"Emulsifier marker."}],"allergens":["soy"],"warnings":[]},"history":[]}'
```

### Latency and NOVA benchmark

Unit tests prove contract and single-call behavior, not production p95. Use the benchmark against a
deployed service before claiming the LLM-only path meets the p95 target or expected NOVA behavior:

```bash
python backend/benchmark_analyze.py \
  https://ultraprocessed-ai-proxy-894254677159.us-east1.run.app \
  --iterations 1 \
  --details
```

The benchmark corpus contains 40 Open Food Facts products: 10 expected NOVA 1, 10 expected NOVA 2,
10 expected NOVA 3, and 10 expected NOVA 4. Expected labels come from Open Food Facts `nova_group`
values, and each corpus row includes the source product code and URL. The script prints success rate, NOVA accuracy,
per-group accuracy, p50, p95, p99, token usage when returned by the backend, and HTTP/model error
counts. Add `--strict-nova` to exit non-zero when any NOVA mismatch is observed.

Corpus source: Open Food Facts public database and API.

## Deploy to Cloud Run

```bash
gcloud run deploy ultraprocessed-ai-proxy \
  --source . \
  --project b2-ultra-processed \
  --region us-east1 \
  --service-account up-app-service@b2-ultra-processed.iam.gserviceaccount.com \
  --allow-unauthenticated \
  --set-env-vars GCP_PROJECT_ID=b2-ultra-processed,GCP_LOCATION=us-east1,GEMINI_MODEL=gemini-3.5-flash
```

The service account must have Vertex AI access (e.g. `roles/aiplatform.user`) on the project.

Deploy from the repository root so the root `Dockerfile` and root `module_versions.json` are packaged into the backend image for `GET /version`. If using `backend/Dockerfile` directly, build with the repository root as the Docker context: `docker build -f backend/Dockerfile .`.

## Security notes

- **Known deferred risk:** `--allow-unauthenticated` leaves `/analyze` and `/chat` publicly callable. This is
  accepted only as a temporary testing or limited-rollout posture. Before broad production launch,
  add real abuse controls such as Firebase App Check / Play Integrity, Firebase Auth, API Gateway
  + IAM, Cloud Armor, per-device quotas, or another approved mechanism.
- **No static mobile secret:** do not protect these endpoints with a secret compiled into the Android
  app; it can be extracted and replayed.
- **Docs are disabled by default.** Set `ENABLE_OPENAPI_DOCS=true` only for local development or
  protected environments.
- **CORS is disabled by default.** Android does not need CORS. Set `CORS_ALLOWED_ORIGINS` only for
  known browser origins in dev/protected environments.
- Public 5xx responses use generic messages. Provider/auth/stack details are kept in server logs.
- No secrets, API keys, or service-account JSON are committed or required at runtime; auth is
  the Cloud Run service-account identity via ADC.

## Notes / caveats

- **Region + model:** Vertex AI regional availability for `gemini-3.5-flash` varies. If a deploy
  reports the model is unavailable in `us-east1`, set `GCP_LOCATION=global` (or `us-central1`)
  via `--set-env-vars` and redeploy.
- **Backend-owned prompts:** Android sends scan data and chat questions only. The analysis and chat prompts live
  in the backend runtime; Android does not send prompt text, schemas, or model instructions.
- **Separate endpoint prompts:** `/analyze` uses the backend-owned full-analysis prompt, and `/chat`
  uses the backend-owned result-chat prompt. The app never sends prompt text.
- **Single structured analysis call:** `/analyze` uses one backend-owned full-analysis prompt
  and Gemini structured JSON output. The prompt still instructs the model to reason internally in the
  order food gate -> cleaned ingredients -> ultra-processed markers -> allergens -> NOVA.
- **Archived staged prompts:** the prior staged analysis prompts are retained only under
  `documentation/prompt-archive/` for reference. They are not runtime backend prompt inputs.
