# Agent Brief

This is the short handoff for agents working in Zest. Use it as the first read, then open the linked subsystem document only when your task touches that area.

## Product And Architecture

Zest is a native Android app that helps grocery shoppers understand packaged-food ingredient labels. It shows a NOVA-style classification, corrected ingredient list, ultra-processed markers, allergens, confidence, warnings, and result-scoped chat.

Runtime flow:

- Camera/gallery image scans run ML Kit OCR on device.
- Images never go to an AI provider.
- Android sends extracted ingredient text, barcode/product hints, or current result chat context to the backend.
- Backend runs Gemini through Vertex AI using Cloud Run service-account auth.
- The default backend model is `gemini-3.5-flash`.
- `/analyze` and `/chat` are separate endpoints with separate backend-owned prompts.
- Scan data is session-only and is not persisted by the app.

## Core Decisions To Preserve

- API-only analysis: no rule-based NOVA fallback in runtime.
- Backend proxy: users do not enter AI API keys.
- Backend-owned prompts: Android never sends prompts, schemas, or model instructions.
- Single-call analysis: `/analyze` returns NOVA, corrected ingredients, ultra-processed markers, allergens, usage, and model id in one structured response.
- Result chat: `/chat` receives the current result, current-session chat history, and one user question; it must stay scan-scoped.
- Privacy: do not persist or log scan images, OCR text, ingredients, results, chat, usage, or failures.
- Storage archive: former Room/history code stays under `documentation/code-archive/session_only_storage/` and must not be restored without a product privacy decision.
- UI: allergens are separate from processing markers; ingredient bubbles are based on corrected ingredient names and ultra-processed marker matches.
- Production risk: public unauthenticated backend endpoints are a known deferred risk and require abuse controls before broad launch.

## Where To Look

- Product scope: `documentation/00-product-requirements.md`
- Architecture: `documentation/01-architecture.md`
- UI/navigation: `documentation/02-ui-navigation.md`
- Camera/OCR/barcode: `documentation/03-camera-ocr-barcode.md`
- Analysis pipeline: `documentation/04-classification-analysis.md`
- Storage/security: `documentation/06-storage-security.md`
- Testing/release: `documentation/07-testing-release.md`
- LLM contracts: `documentation/08-llm-api-contracts.md`
- Roadmap/cautions: `documentation/09-todo-roadmap.md`
- Maintenance: `documentation/11-maintenance-plan.md`
- Module versioning: `documentation/12-module-versioning.md`
- Decision log: `documentation/14-decision-log.md`
- Backend: `backend/README.md`

## Implementation Guardrails

- Prefer existing package boundaries: `ui`, `analysis`, `network/llm`, `network/usda`, `ocr`, `camera`, `storage/preferences`, and `storage/secrets`.
- Keep prompts in `backend/prompts/`.
- Keep benchmark data in backend-owned benchmark files, not app runtime code.
- Keep transient image cleanup intact after success, failure, result close, teardown, and relaunch cleanup.
- Do not add persistent diagnostics unless they are privacy-safe and contain no user scan content.
- Any new module or top-level Android package must be added to root `module_versions.json`, root `VERSION_LOG.md`, and `documentation/12-module-versioning.md`.
- Every change must update the relevant summary documentation, extract durable decisions into `documentation/14-decision-log.md`, and update this brief when future agents need the new guidance.
- Version-affecting changes must update root `module_versions.json`, root `VERSION_LOG.md`, and `documentation/12-module-versioning.md`.

## Verification Defaults

Run the smallest relevant set:

```bash
./gradlew :app:verifySourceTreeForBuild :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
.venv/bin/python -m pytest backend/tests/test_app.py backend/tests/test_benchmark_analyze.py -q
git diff --check
```

For backend latency/NOVA checks against a deployed service:

```bash
python backend/benchmark_analyze.py \
  https://ultraprocessed-ai-proxy-894254677159.us-east1.run.app \
  --iterations 1 \
  --details
```

Use `--strict-nova` only when you want benchmark mismatches to fail the command.
