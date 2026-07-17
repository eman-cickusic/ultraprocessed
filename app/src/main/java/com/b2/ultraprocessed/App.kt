package com.b2.ultraprocessed

import android.app.Application
import com.b2.ultraprocessed.network.llm.AppCheckTokenProvider

/**
 * Application entry point. Installs Firebase App Check (Play Integrity) at startup so proxy requests
 * can carry an attestation token. This is a no-op until `google-services.json` is present; see
 * [AppCheckTokenProvider] and `documentation/15-backend-abuse-controls.md`.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCheckTokenProvider.initialize(this)
    }
}
