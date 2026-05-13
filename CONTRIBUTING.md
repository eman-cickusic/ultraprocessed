# Contributing Guidelines

Thank you for contributing to Zest. This project is an Android app built with Kotlin, Jetpack Compose, Room, CameraX, ML Kit OCR/barcode scanning, and LLM-based ingredient analysis.

## Getting Started

1. Clone the repository.

   git clone https://github.com/benevolentbandwidth/ultraprocessed.git
   cd ultraprocessed

2. Open the project in Android Studio.

3. Let Gradle sync fully before making changes.

4. Run a debug build.

   ./gradlew :app:assembleDebug

## Development Principles

- Keep OCR on-device. Images must not be sent to external APIs.
- LLM calls should receive extracted text or corrected ingredient names only.
- Avoid hardcoded result fallbacks or rule-based classification logic.
- Preserve the existing dark theme, emerald accent color, typography scale, and 8pt spacing grid.
- Reuse existing UI components before creating new visual patterns.
- Keep user-facing language clear, friendly, and non-medical.
- Do not remove safety disclaimers or allergy warnings.

## Code Style

- Use Kotlin idioms and Jetpack Compose best practices.
- Prefer small composables with clear responsibilities.
- Keep UI text in `strings.xml`.
- Keep prompts in `app/src/main/assets/prompts/`.
- Avoid unnecessary comments; add comments only when logic is not obvious.
- Do not introduce dead code, demo code, or legacy placeholder flows.

## Branching

Use short, descriptive branch names:

- `feature/history-rerun`
- `fix/classification-timeout`
- `ui/settings-polish`
- `docs/android-guide`

## Before Submitting

Run the following checks:

- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:compileDebugAndroidTestKotlin`

For release-sensitive changes, also run:

- `./gradlew :app:compileReleaseKotlin`
- `./gradlew :app:minifyReleaseWithR8`

## Pull Request Checklist

Before opening a PR, confirm:

- [ ] The app builds successfully.
- [ ] Unit tests pass.
- [ ] Android test Kotlin compilation passes.
- [ ] No images are sent to model APIs.
- [ ] UI follows the existing theme and spacing system.
- [ ] New strings are added to `strings.xml`.
- [ ] Prompts remain strict JSON contracts where required.
- [ ] Documentation is updated if behavior changes.
- [ ] No redundant, dead, demo, or legacy code was added.
- [ ] No secrets, API keys, or local-only files are committed.

## UI Contribution Rules

- Use the existing dark background and emerald accent palette.
- Follow the shared typography scale.
- Use the 8pt grid for margins and padding.
- Keep cards, pills, and buttons visually consistent across pages.
- Ingredient chips should remain compact and readable.
- Avoid oversized text or overly bright card backgrounds.
- Use icons consistently where existing sections use icons.

## LLM Pipeline Rules

The app uses separate LLM stages:

1. NOVA classification.
2. Ingredient name correction and ultra-processed ingredient detection.
3. Allergen detection.
4. Chat on demand.

Contributors should not merge these stages unless explicitly discussed.

API calls should use deterministic settings:

- `temperature: 0`
- `top_p: 1`
- `frequency_penalty: 0`
- `presence_penalty: 0`

Retries should be used only for timeout-style failures.

## Privacy And Safety

This app is privacy-first:

- Images stay on device.
- OCR runs locally.
- Scan history is stored locally.
- API keys are stored securely.
- External APIs receive only text required for analysis.

Do not add features that weaken this privacy model without explicit approval.

## Documentation

If your change affects architecture, setup, storage, prompts, UI flow, or API behavior, update the relevant documentation under:

`documentation/`

Key docs include:

- `00-android-app-guide.md`
- `02-ui-navigation.md`
- `04-classification-analysis.md`
- `06-storage-security.md`
- `08-llm-api-contracts.md`
- `09-todo-roadmap.md`

## Commit Messages

Use concise, descriptive commit messages.

Examples:

- `Refine scanner controls and barcode mode`
- `Add exact provider token usage tracking`
- `Polish settings cards and app theming`
- `Update LLM prompt contracts for NOVA analysis`

## Reporting Issues

When reporting a bug, include:

- Device or emulator model.
- Android version.
- Steps to reproduce.
- Expected behavior.
- Actual behavior.
- Screenshots or logs if available.
- Whether it happens in debug, release, or both.

## Review Expectations

Code review should focus on:

- Correctness.
- User safety.
- Privacy.
- UI consistency.
- Release risk.
- Test coverage.
- Maintainability.

Be kind, specific, and practical in review comments.
