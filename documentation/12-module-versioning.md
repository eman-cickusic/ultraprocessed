# Module Versioning

Zest tracks module versions through one machine-readable manifest:

```text
module_versions.json
VERSION_LOG.md
```

The root manifest is the production source of truth. The root version log records why release and module versions changed. The manifest is packaged with the Cloud Run backend, exposed through `GET /version`, and checked by the Android release guard so new source, documentation, or agent-guidance modules cannot be added without an explicit version entry.

## Current Module Versions

| Module ID | Kind | Path | Version | Runtime Surface |
| --- | --- | --- | --- | --- |
| `android-app` | android-gradle-module | `:app` | `1.0.2` | Android `BuildConfig.VERSION_NAME` |
| `backend-proxy` | cloud-run-service | `backend` | `1.0.2` | FastAPI `app.version` and `GET /version` |
| `analysis` | android-package | `app/src/main/java/com/b2/ultraprocessed/analysis` | `1.0.1` | module version manifest |
| `barcode` | android-package | `app/src/main/java/com/b2/ultraprocessed/barcode` | `1.0.0` | module version manifest |
| `camera` | android-package | `app/src/main/java/com/b2/ultraprocessed/camera` | `1.0.0` | module version manifest |
| `classify` | android-package | `app/src/main/java/com/b2/ultraprocessed/classify` | `1.0.0` | module version manifest |
| `ingredients` | android-package | `app/src/main/java/com/b2/ultraprocessed/ingredients` | `1.0.0` | module version manifest |
| `network` | android-package | `app/src/main/java/com/b2/ultraprocessed/network` | `1.0.0` | module version manifest |
| `ocr` | android-package | `app/src/main/java/com/b2/ultraprocessed/ocr` | `1.0.0` | module version manifest |
| `storage` | android-package | `app/src/main/java/com/b2/ultraprocessed/storage` | `1.0.0` | module version manifest |
| `ui` | android-package | `app/src/main/java/com/b2/ultraprocessed/ui` | `1.0.1` | module version manifest |
| `backend-prompts` | backend-contract-assets | `backend/prompts` | `1.0.3` | backend-owned prompt files |
| `documentation` | repo-documentation | `documentation` | `1.0.0` | repo documentation and handoff docs |
| `agent-context` | repo-agent-guidance | `AGENTS.md` | `1.0.0` | `AGENTS.md` and `.cursor/rules/zest-project.mdc` |

## Production Tracking Contract

- Every Gradle module must have one manifest entry with `kind = android-gradle-module`.
- Every top-level Android package under `app/src/main/java/com/b2/ultraprocessed` must have one manifest entry with `kind = android-package`.
- Every deployable backend service must have one manifest entry with a runtime endpoint that can report the deployed version.
- Backend prompt contracts are versioned separately from the backend service because prompt changes can alter model behavior without changing API transport.
- Documentation and agent guidance are versioned because they are now production handoff surfaces for future agents.
- Documentation must include each manifest module ID and version.
- `VERSION_LOG.md` must include the current `releaseVersion` and the module versions changed by that release.

## Verification

Android `verifySourceTreeForBuild` runs `verifyModuleVersionManifest`. The task fails when:

- root `module_versions.json` is missing or invalid,
- root `VERSION_LOG.md` is missing the current release or changed module versions,
- a top-level Android package exists without a version entry,
- a version entry points to a missing path,
- a Gradle module exists without a manifest entry,
- documentation is missing a module ID or version from the manifest.

Backend tests verify:

- FastAPI `app.version` matches `releaseVersion`,
- `GET /version` returns the manifest,
- `/version` does not expose request data, prompt bodies, OCR text, or model responses.

## Version Bump Rules

- Bump `android-app` when changing app behavior, UI, API contract handling, permissions, storage, release config, or bundled assets.
- Bump `backend-proxy` when changing request validation, endpoint behavior, model configuration, response contracts, deployment config, auth, or observability.
- Bump `backend-prompts` when changing prompt instructions, schemas, examples, model-output expectations, or chat safety behavior.
- Bump an Android package module when changing public behavior owned by that package.
- Bump `documentation` when changing production docs, handoff docs, architecture guidance, or testing/release instructions.
- Bump `agent-context` when changing `AGENTS.md`, Cursor rules, or other agent bootstrap instructions.

Every change must also update the relevant documentation summary and extract any durable philosophy or architecture decision into `documentation/14-decision-log.md`. If the change affects how future agents should work, update `documentation/13-agent-brief.md`.

All module versions use semantic versioning. For coordinated releases, `releaseVersion` should match the app version shipped to users. Individual modules can move faster only when a change is internal and does not require a full app release.
