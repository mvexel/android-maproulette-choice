package lol.osm.streettally.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred
import lol.osm.streettally.MainActivity

/** The browser step of sign-in: opens the authorization URL in a Custom Tab and waits for the
 * OAuth callback, which [RedirectActivity] receives. Coming back to the app without a callback
 * (the tab was closed) counts as canceled. Main thread only. */
object BrowserSignIn {
    private var pending: CompletableDeferred<String>? = null
    private var leftApp = false

    /** Whether the default browser supports private (ephemeral) Custom Tabs, which share no cookies,
     * so each sign-in shows the OpenStreetMap login and any account can be chosen. */
    fun privateTabsSupported(context: Context): Boolean = runCatching {
        val browser = CustomTabsClient.getPackageName(context, null) ?: return false
        CustomTabsClient.isEphemeralBrowsingSupported(context, browser)
    }.getOrDefault(false)

    suspend fun authenticate(activity: Activity, url: String): String {
        pending?.completeExceptionally(IllegalStateException("superseded"))
        val callback = CompletableDeferred<String>()
        pending = callback
        leftApp = false
        try {
            CustomTabsIntent.Builder()
                .apply { if (privateTabsSupported(activity)) setEphemeralBrowsingEnabled(true) }
                .build()
                .launchUrl(activity, Uri.parse(url))
            return callback.await()
        } finally {
            if (pending === callback) pending = null
        }
    }

    internal fun onCallback(uri: Uri) {
        pending?.complete(uri.toString())
    }

    /** Called from the host activity's onPause / onResume. */
    fun onHostPaused() {
        if (pending != null) leftApp = true
    }

    fun onHostResumed() {
        if (leftApp) pending?.completeExceptionally(IllegalStateException("browser closed"))
    }
}

/** Receives `<callback scheme>:/oauth2redirect?...`, hands it to [BrowserSignIn] and returns to the
 * app, closing the Custom Tab. */
class RedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(BrowserSignIn::onCallback)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
