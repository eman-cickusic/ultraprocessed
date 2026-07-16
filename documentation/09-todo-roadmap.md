# To-Do And Roadmap

This backlog is based on the current codebase. It separates engineering cleanup from product work so v2 planning does not blur infrastructure tasks with user-facing features.

## Current State

Zest currently uses:

- a Compose app shell in `ui/UltraProcessedApp.kt`,
- a simple `AppDestination` enum,
- `BackHandler` to keep Android system back and left-edge swipe inside the app,
- session-only scan results with the former Room/History capability archived,
- on-device OCR with ML Kit,
- text-only backend proxy LLM calls,
- local usage/cost estimates,
- encrypted API key storage,
- non-persistent failure states with transient image cleanup.

## To-Do List

### Engineering Foundation

- Add centralized navigation stack.
- Add navigation-level tests for system back, edge-swipe, and screen-origin behavior.
- Create typed screen arguments for result, disclaimer, and analysis flows.
- Move analysis launch side effects out of screen composition where practical.
- Design privacy-safe operational diagnostics that never include OCR text, images, prompts, or model responses.
- Finish production abuse controls for public backend `/analyze` and `/chat` before broad launch. In progress (see [12-backend-abuse-controls.md](12-backend-abuse-controls.md)): max-instances capped, and a Firebase App Check / Play Integrity verifier is landed in the backend but gated off. Remaining: Firebase/Play setup, the Android token header, then flip `APP_CHECK_ENABLED`.
- Add provider usage parsing when provider responses include reliable token/cost metadata.
- Add migration tests only if a future approved feature restores local persistence.
- Add UI snapshot or screenshot tests for scanner, settings, results, and error states.

### Product Polish

- Improve non-food scan error copy with scan tips.
- Add richer NOVA education copy on the results screen.
- Add a share/export path for scan summaries.

## V2 Engineering Feature: Adding Navigation Stack (Centralised)

### Why This Matters

The app currently stores:

```text
destination: AppDestination
previousDestination: AppDestination?
```

This works for the present flow, but it is not a real stack. It handles Scanner, Results, Settings, Disclaimer, AnalysisError, and Analyzing well enough today, but it will become fragile as soon as we add nested result details, product education pages, onboarding, account-like settings sections, or multiple entry points into the result flow.

A centralized navigation stack should become the single source of truth for:

- current screen,
- previous screens,
- typed screen arguments,
- back behavior,
- deep-link-like internal routing,
- session-safe result routing without restoring scan content after process recreation,
- analytics events for screen transitions,
- test assertions for navigation.

### Current Behavior To Preserve

- Scanner is the primary home screen.
- Splash routes to Scanner after the loading animation.
- Settings back returns to the previous app page when possible.
- Results back returns to Scanner.
- AnalysisError back returns to Scanner and clears the error message.
- Analyzing back returns to Scanner.
- Scanner consumes system back as a no-op so accidental edge swipes do not close the app.

### Proposed Shape

Introduce a small navigation controller before adopting a heavy framework:

```text
AppNavigator
├── stack: List<AppRoute>
├── current: AppRoute
├── push(route)
├── replace(route)
├── pop()
├── popToScanner()
└── canPop
```

Use typed routes instead of a bare enum:

```kotlin
sealed interface AppRoute {
    data object Splash : AppRoute
    data object Scanner : AppRoute
    data class Analyzing(val mode: AnalysisMode, val imagePath: String?, val barcode: String?) : AppRoute
    data object Results : AppRoute
    data class AnalysisError(val message: String) : AppRoute
    data object Settings : AppRoute
    data object Disclaimer : AppRoute
}
```

The exact route model can be tuned, but the important part is that navigation state and screen arguments move together.

### Implementation Steps

1. Create `ui/navigation/AppRoute.kt` and `ui/navigation/AppNavigator.kt`.
2. Move transition helpers out of `UltraProcessedApp.kt`.
3. Replace `destination` and `previousDestination` with navigator state.
4. Route all visible back buttons through the same navigator function used by `BackHandler`.
5. Add tests for every back path.
6. Add process-restoration coverage for current route and important arguments.
7. Update documentation diagrams once the stack lands.

### Risks

- Analysis launches currently depend on Compose state such as `lastCapturedPhotoPath`, `barcodeValue`, `scanSessionId`, and `analysisMode`.
- Moving too much at once may destabilize scan/rerun behavior.
- Route arguments must not carry secrets or large bitmap data.
- Saved routes must not store OCR text, image paths, chat context, or scan-result payloads.

### Acceptance Criteria

- Every screen transition goes through one centralized navigator.
- Android system back and edge swipe behave the same as visible back buttons.
- Scanner remains protected from accidental app exit.
- Opening Disclaimer from Settings and pressing back returns to Settings.
- Process recreation does not restore scan content, images, chat context, or usage metadata.
- Unit or Compose tests cover key navigation paths.

## Other Engineering Features To Move To V2

- Provider usage metadata: map exact token/cost values from Gemini/OpenAI-compatible responses when available instead of relying only on `UsageEstimateCalculator`.
- Privacy-safe diagnostics: expose app version, service health, and generic failure categories without OCR text, prompts, images, or model responses.
- Prompt contract versioning: include backend prompt/model version in transient responses for debugging without persisting scan rows.
- Provider capability registry: move hardcoded provider/model rules into one typed registry with supported parameters, endpoint shape, and pricing metadata.
- More robust in-session result modeling: keep staged outputs separate from final `ScanResultUi` mapping without writing scan data to disk.
- Background-safe analysis jobs: make long-running analysis resilient to Activity recreation.
- Centralized UI tokens: formalize spacing, radius, elevation, opacity, and component sizing beyond the current shared text/color files.
- Accessibility pass: semantic labels, contrast audit, dynamic text behavior, and scanner action descriptions.
- Screenshot testing: capture canonical screen states for Scanner, Results, Settings, Splash, AnalysisError, and empty states.
- Release observability: local diagnostic screen for app version, model selected, service readiness, and generic failure categories.

## Other Product Features

- Scan education: short NOVA explanations and examples next to classification results.
- Result comparison: compare two active-session scans side by side if future session state supports it.
- Export scan: share a clean summary as text or image.
- Allergen preferences: user-selected allergens to highlight more prominently.
- Household mode: local profiles with different allergen preferences.
- Barcode-first product recognition: improve UPC/EAN lookup and explain unavailable products without storing scan history.
- Offline queue: out of scope while the no-human-data-storage policy is active because it would persist images or OCR text.
- Feedback loop: allow users to flag incorrect ingredient cleanup, NOVA classification, or allergen detection for review.
- Guided capture: smarter hints when OCR sees nutrition facts, marketing copy, or non-food text.
- Product alternatives: after a result, suggest what kind of ingredient list would be less processed without naming unsupported products.
