# Version Log

This is the repo-level change log for Zest module and release versions. Every code, prompt, architecture, documentation, or agent-guidance change must either add an entry here or explicitly state that no version bump was required.

Entries can represent coordinated product releases or independently versioned modules.

## 1.0.3

Date: 2026-07-13

- Optimized the backend full-analysis prompt contract for single-call NOVA decisioning, lower latency, and benchmark-driven accuracy.

## 1.0.2

Date: 2026-07-13

- Moved the module version manifest source of truth to the repo root: `module_versions.json`.
- Added repo-level version logging in `VERSION_LOG.md`.
- Added agent governance requiring every future change to summarize implementation, extract decisions/philosophy, and update the relevant documentation or decision file.
- Added documentation and agent-context modules to version tracking.
- Updated backend packaging to use the repo-root Docker build context and packaged root manifest for `/version`.

## 1.0.1

Date: 2026-07-13

- Switched the backend and Android model defaults from Gemini 2.5 Flash to `gemini-3.5-flash`.
- Updated backend deployment documentation and Android proxy fixtures for the Gemini 3.5 Flash default.
- Added module-version tracking and source-tree verification guard.

## 1.0.0

Date: 2026-07-13

- Baseline documented release after backend proxy, session-only storage, backend-owned prompts, separate `/analyze` and `/chat`, and no-user-AI-key architecture were established.
