package lol.osm.streettally

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.auth.KeystoreCredentialStore
import lol.osm.streettally.campaign.CampaignPrefs
import lol.osm.streettally.campaign.CheckedLog
import lol.osm.streettally.demo.DemoBackend
import lol.osm.streettally.task.ClientTaskOps
import lol.osm.streettally.task.GuestOutbox
import org.maproulette.sdk.OkHttpTransport

/** Holds the process-wide session, so sign-in survives activity recreation. */
class StreetTallyApp : Application() {
    val scope: CoroutineScope = MainScope()
    lateinit var session: AppSession
        private set
    lateinit var checked: CheckedLog
        private set
    lateinit var prefs: CampaignPrefs
        private set
    /** A guest's answers that couldn't be sent yet; see [sendQueued]. */
    lateinit var outbox: GuestOutbox
        private set
    /** The demo build's in-process backend; null in every other build. */
    var demo: DemoBackend? = null
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = CampaignPrefs(this)
        outbox = GuestOutbox(prefs.outboxStore)
        checked = CheckedLog(prefs.checkedCounts)
        val config = AppConfig.fromBuild()
        session = AppSession(
            config,
            KeystoreCredentialStore(this, "${config.baseUrl}|${config.oauthClientId}"),
            if (config.demo) {
                DemoBackend(
                    DemoBackend.ASSETS.mapValues { (_, asset) -> assets.open(asset).bufferedReader().use { it.readText() } },
                    log = { Log.d("StreetTallyDemo", it) },
                ).also { demo = it }
            } else {
                OkHttpTransport()
            },
        )
        scope.launch {
            session.restore()
            // A guest who signed in but whose answers weren't claimed yet (offline then): try again.
            session.claimKeptGuest()
            sendQueued()
        }
        // Back online: send what's waiting. Best effort; the app opening tries again.
        (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { sendQueued() }
                }
            },
        )
    }

    /** Sends a guest's waiting answers, if any, while still a guest. Signed in, they stay queued
     * (the guest token can't send them any more), and the claim covers those that reached the server. */
    fun sendQueued() {
        scope.launch { sendQueuedNow() }
    }

    /** [sendQueued], waiting for it to finish. Best effort: never throws but for cancellation. */
    suspend fun sendQueuedNow() {
        if (outbox.entries.value.isEmpty() || !session.state.value.restored || session.writer()?.guest != true) return
        try {
            if (outbox.send(ClientTaskOps(session.client())) > 0) session.refreshGuestStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** "I already use OpenStreetMap": answers still waiting on the phone are sent first, while still a
     * guest, so they go with the sign-in like the saved ones (offline-copy.md). */
    suspend fun signIn(authenticate: suspend (String) -> String) {
        sendQueuedNow()
        session.signIn(authenticate)
    }
}
