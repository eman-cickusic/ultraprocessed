package com.b2.ultraprocessed.network.llm

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.tasks.await

/**
 * Firebase App Check (Play Integrity) attestation for the Cloud Run proxy calls.
 *
 * Dormant until Firebase is configured: [initialize] installs the Play Integrity provider only when a
 * default [FirebaseApp] exists (i.e. `google-services.json` has been added to `:app`). [currentToken]
 * returns null whenever attestation is unavailable, so callers simply omit the header and the request
 * still works while the backend enforcement flag (`APP_CHECK_ENABLED`) is off. Once Firebase + Play
 * Integrity are set up and the backend flag is on, this token is what proves a request came from the
 * genuine, unmodified app.
 *
 * See `documentation/15-backend-abuse-controls.md` for the rollout order.
 */
object AppCheckTokenProvider {

    const val X_FIREBASE_APPCHECK_HEADER = "X-Firebase-AppCheck"

    /** Install the Play Integrity provider if Firebase is configured; otherwise stay dormant. */
    fun initialize(context: Context) {
        val firebaseApp = runCatching { FirebaseApp.getInstance() }.getOrNull()
            ?: runCatching { FirebaseApp.initializeApp(context) }.getOrNull()
            ?: return
        runCatching {
            FirebaseAppCheck.getInstance(firebaseApp)
                .installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance(),
                )
        }
    }

    /** A fresh App Check token, or null when attestation is unavailable (e.g. Firebase not configured). */
    suspend fun currentToken(): String? = runCatching {
        FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token.ifBlank { null }
    }.getOrNull()
}
