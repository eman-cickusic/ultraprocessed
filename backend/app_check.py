"""Firebase App Check (Play Integrity) token verification for the Zest backend.

This is the abuse control called for before broad rollout: verifying an App Check
token is what distinguishes a genuine, unmodified instance of the Android app from
a script hitting the public Cloud Run URL. The Android app obtains the token via the
Play Integrity provider and sends it in the `X-Firebase-AppCheck` header; this module
verifies it before `/analyze` or `/chat` reach the paid Vertex call.

Gated OFF by default (`APP_CHECK_ENABLED`). It stays a no-op until BOTH the backend
flag is on AND the shipped app sends tokens, so it can be deployed safely ahead of
the client change without breaking the live app. See
`documentation/15-backend-abuse-controls.md` for the rollout order.
"""

from __future__ import annotations

import logging
import os
from typing import Optional

from fastapi import Header, HTTPException

logger = logging.getLogger("ultraprocessed-ai-proxy.appcheck")

APP_CHECK_ENABLED = os.getenv("APP_CHECK_ENABLED", "false").strip().lower() == "true"
# Firebase project number (== GCP project number when Firebase is added to this project).
FIREBASE_PROJECT_NUMBER = os.getenv("FIREBASE_PROJECT_NUMBER", "").strip()
# Stable Google endpoint serving the App Check public keys; override only for testing.
APP_CHECK_JWKS_URL = os.getenv(
    "APP_CHECK_JWKS_URL", "https://firebaseappcheck.googleapis.com/v1/jwks"
)
_ISSUER_PREFIX = "https://firebaseappcheck.googleapis.com/"

_jwks_client = None


def _get_jwks_client():
    """Cache the JWKS client; PyJWKClient caches the fetched keys internally."""
    global _jwks_client
    if _jwks_client is None:
        from jwt import PyJWKClient

        _jwks_client = PyJWKClient(APP_CHECK_JWKS_URL, cache_keys=True)
    return _jwks_client


def verify_app_check_token(token: str) -> dict:
    """Verify a Firebase App Check JWT and return its claims, or raise on any failure.

    Checks RS256 signature against Google's published keys, plus the audience and
    issuer bound to this project. Monkeypatched in tests so the suite needs no
    network or real tokens.
    """
    import jwt

    signing_key = _get_jwks_client().get_signing_key_from_jwt(token).key
    return jwt.decode(
        token,
        signing_key,
        algorithms=["RS256"],
        audience=f"projects/{FIREBASE_PROJECT_NUMBER}",
        issuer=f"{_ISSUER_PREFIX}{FIREBASE_PROJECT_NUMBER}",
        leeway=10,  # small clock-skew tolerance
        options={"require": ["exp", "iat"]},
    )


def require_app_check(x_firebase_appcheck: Optional[str] = Header(default=None)) -> None:
    """FastAPI dependency: no-op when disabled, enforces a valid token when enabled.

    Fails CLOSED — if enforcement is on but misconfigured, requests are denied, never
    allowed through unchecked.
    """
    if not APP_CHECK_ENABLED:
        return
    if not FIREBASE_PROJECT_NUMBER:
        logger.error("APP_CHECK_ENABLED=true but FIREBASE_PROJECT_NUMBER is unset; denying request.")
        raise HTTPException(
            status_code=500,
            detail={"error": "app_check_misconfigured", "message": "App attestation is misconfigured."},
        )
    if not x_firebase_appcheck:
        raise HTTPException(
            status_code=401,
            detail={"error": "app_check_required", "message": "App attestation is required."},
        )
    try:
        verify_app_check_token(x_firebase_appcheck)
    except HTTPException:
        raise
    except Exception as exc:  # bad signature, expired, wrong aud/iss, malformed token
        logger.warning("App Check token rejected: %s", type(exc).__name__)
        raise HTTPException(
            status_code=401,
            detail={"error": "app_check_invalid", "message": "App attestation failed."},
        )
