# Backend Abuse Controls

Owner: Eman Cickusic. This tracks closing the one production caution left after the GCP backend
migration: the public Cloud Run proxy (`/analyze`, `/chat`) still needs abuse controls before broad
rollout. It records the current exposure, what is already mitigated, the chosen control, and the exact
ordered steps to finish — so the cutover does not break the shipped app.

## Current exposure (verified 2026-07-16)

- Service `ultraprocessed-ai-proxy`, region `us-east1`, project `b2-ultra-processed` (number `894254677159`).
- Cloud Run IAM: `allUsers` → `roles/run.invoker`. Fully public.
- Ingress: `all`. No App Check, Firebase, API Gateway, or load balancer exists in the project.
- Every `/analyze` and `/chat` call triggers a **paid Vertex Gemini** call. No auth, no attestation, no rate limit.
- The proxy URL ships inside the APK (`PROXY_BASE_URL` in `app/build.gradle.kts`), so treat it as public knowledge.
- **Threat:** anyone can script the endpoints → unbounded Vertex cost and quota exhaustion (a DoS for real users).

## Mitigations already in place

1. **Blast radius capped.** Cloud Run `--max-instances` lowered `100 → 10` (revision `-00004`). Bounds how many
   concurrent paid calls a flood can drive (~10× reduction). This limits *damage*, not *access*, and is tunable:
   `gcloud run services update ultraprocessed-ai-proxy --region=us-east1 --project=b2-ultra-processed --max-instances=N`.
2. **App Check verifier landed, gated OFF.** `backend/app_check.py` + a `require_app_check` dependency now guard both
   POST routes. With `APP_CHECK_ENABLED=false` (default) it is a no-op — the shipped app, which sends no token, keeps
   working. When enabled it verifies a Firebase App Check token and **fails closed** if misconfigured. Covered by
   `backend/tests/test_app_check.py`.

## The chosen control: Firebase App Check with Play Integrity

Attests that a request comes from a genuine, unmodified instance of *our* Android app — the right fit for an
anonymous, Android-only backend. Why not the alternatives:

- **Firebase Auth** — no user accounts in this app; overkill.
- **API Gateway + IAM / a static key in the app** — any secret compiled into the APK can be extracted and replayed
  (see the backend README rule). Rejected.
- **Cloud Armor** — needs an external HTTPS load balancer in front of Cloud Run (none exists), and IP rate limits are
  weak behind mobile-carrier NAT. Heavier; keep only as a later option if IP/geo/WAF controls are ever needed.
- **In-process rate limiting** — Cloud Run autoscales, so per-instance memory counters don't hold; would need
  Memorystore/Redis. Defer unless abuse persists *after* App Check.

Verification implemented (per Google's "verify App Check tokens from a custom backend"): RS256 JWT sent in the
`X-Firebase-AppCheck` header, signature checked against Google's JWKS, audience `projects/894254677159`, issuer
`https://firebaseappcheck.googleapis.com/894254677159`.

## Remaining work to turn it on — ordered, do not reorder

Reordering breaks the live app: if the backend enforces before the shipped app sends tokens, every real user gets 401.

1. **Firebase + Play setup** (Firebase / Play Console access required):
   - Add Firebase to the existing GCP project `b2-ultra-processed` (same project number).
   - Enable APIs `firebase.googleapis.com` and `firebaseappcheck.googleapis.com`.
   - Register the Android app (package `com.b2.ultraprocessed`); download `google-services.json`.
   - Link the app in Play Console, enable the Play Integrity API, and register the App Check Play Integrity provider.
2. **Android change** (not done here — needs `google-services.json` and the Firebase project):
   - Add the Google Services Gradle plugin, Firebase BoM, `firebase-appcheck`, and `firebase-appcheck-playintegrity`.
   - Initialize App Check with the Play Integrity provider in `Application.onCreate`.
   - Attach the token to each proxy call. Both `ProxyFoodLabelLlmWorkflow` and `ProxyResultChatWorkflow` currently
     set only `Content-Type`, so this is one header per request (coroutines-play-services is already a dependency):
     ```kotlin
     val token = FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
     // ...Request.Builder().header("X-Firebase-AppCheck", token)
     ```
   - On token-fetch failure, route through the existing "service unavailable" error path.
   - Ship this build to production and let adoption build (older installs still send no token).
3. **Flip the backend flag** (after the token-sending build has meaningful adoption):
   - Redeploy with `--set-env-vars APP_CHECK_ENABLED=true,FIREBASE_PROJECT_NUMBER=894254677159` (keep the `GEMINI_*` vars).
   - Use App Check **monitor mode** in the Firebase console first to measure how much traffic is unverified before enforcing.
4. **Optional hardening after App Check holds:** revisit per-device quotas/rate limits only if abuse continues;
   add Cloud Armor + an external LB only if IP/geo/WAF controls become necessary.

## Cost guardrail (recommended, manual)

Billing is enabled (account `013337-E67B0A-EFBA0E`), but budgets need the budgets API plus a billing-account role that
project-level IAM does not grant — so this is a manual step for someone with billing access:

```
gcloud services enable billingbudgets.googleapis.com --project=b2-ultra-processed
gcloud billing budgets create --billing-account=013337-E67B0A-EFBA0E \
  --display-name="ultraprocessed Vertex guard" \
  --budget-amount=50USD \
  --threshold-rule=percent=0.5 --threshold-rule=percent=0.9 --threshold-rule=percent=1.0
```

## Least-privilege note (separate cleanup)

The runtime service account `up-app-service@b2-ultra-processed.iam.gserviceaccount.com` holds **both**
`roles/aiplatform.user` and legacy `roles/ml.developer`. `aiplatform.user` already covers Vertex; the legacy role
looks redundant. After confirming nothing uses the legacy AI Platform API, remove it:

```
gcloud projects remove-iam-policy-binding b2-ultra-processed \
  --member=serviceAccount:up-app-service@b2-ultra-processed.iam.gserviceaccount.com \
  --role=roles/ml.developer
```

## Verify

- Backend: `cd backend && pytest` — `tests/test_app_check.py` covers disabled-passthrough, missing/invalid/valid
  token, fail-closed-when-misconfigured, `/chat` protected, and `/healthz` open.
- After enabling: `curl -X POST .../analyze` with no token → `401 app_check_required`; the app (with a valid token) → `200`.
