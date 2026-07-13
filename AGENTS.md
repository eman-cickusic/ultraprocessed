# Zest Agent Instructions

Start every repo task by reading:

1. `documentation/13-agent-brief.md`
2. `documentation/14-decision-log.md`
3. The subsystem document linked from the brief for the area you are changing.

## Non-Negotiable Rules

- Do not add rule-based NOVA classification or fallback paths to runtime code.
- Do not send captured or imported images to any AI provider. OCR runs on device; the backend receives text only.
- Do not persist scan images, OCR text, normalized ingredients, results, chat, usage, or failures.
- Do not log OCR text, prompts, model requests/responses, user questions, or result context.
- Do not ask users for AI model API keys. Gemini calls go through the backend proxy using Cloud Run Vertex auth.
- Keep analysis and chat prompts backend-owned. Android must not send prompt text or schemas.
- Keep `/analyze` and `/chat` separate backend endpoints with separate prompts.
- Keep allergens separate from processing markers in UI and contracts.
- Keep module versions tracked in root `module_versions.json`, logged in root `VERSION_LOG.md`, and documented in `documentation/12-module-versioning.md`.

## Change Governance

Every change must leave a compact handoff trail:

- Summarize what changed in the relevant documentation.
- Extract the design philosophy or architecture decision behind the change and update `documentation/14-decision-log.md` when it creates or changes a durable decision.
- Update `documentation/13-agent-brief.md` when the change affects how future agents should approach the repo.
- Update root `module_versions.json` and root `VERSION_LOG.md` whenever behavior, public contracts, prompts, build/release rules, documentation modules, or agent guidance changes.
- If no version bump is needed, say why in the final response.

## Current Runtime Shape

- Android app: native Kotlin + Jetpack Compose.
- Image path: camera/gallery image -> on-device ML Kit OCR -> text-only backend `/analyze`.
- Barcode path: USDA lookup when configured -> backend `/analyze`.
- Chat path: current result + current session chat history -> backend `/chat`.
- Backend: FastAPI on Cloud Run, Vertex AI via service-account identity, default model `gemini-3.5-flash`.
- Storage: session-only scan data; former Room/history code is archived under `documentation/code-archive/session_only_storage/`.

## Verification Commands

Use the smallest relevant set first:

```bash
./gradlew :app:verifySourceTreeForBuild :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
.venv/bin/python -m pytest backend/tests/test_app.py backend/tests/test_benchmark_analyze.py -q
git diff --check
```

Gradle may need access to `~/.gradle` caches. If sandboxed execution fails on Gradle lock files, rerun with the approved Gradle command outside the sandbox.

## Known Production Caution

The deployed Cloud Run `/analyze` and `/chat` endpoints are documented as a temporary limited-rollout posture if publicly callable. Broad production needs an abuse-control layer such as Firebase App Check / Play Integrity, Firebase Auth, API Gateway/IAM, Cloud Armor, quotas, or rate limiting.
