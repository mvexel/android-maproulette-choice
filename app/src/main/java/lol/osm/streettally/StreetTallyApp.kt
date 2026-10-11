package lol.osm.streettally

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.auth.KeystoreCredentialStore
import lol.osm.streettally.campaign.CampaignPrefs
import lol.osm.streettally.campaign.CheckedLog
import org.maproulette.sdk.OkHttpTransport
import lol.osm.streettally.task.PendingSaveStore

/** Holds the process-wide session, so sign-in survives activity recreation. */
class StreetTallyApp : Application() {
    val scope: CoroutineScope = MainScope()
    lateinit var session: AppSession
        private set
    val checked = CheckedLog()
    lateinit var prefs: CampaignPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = CampaignPrefs(this)
        val config = AppConfig.fromBuild()
        session = AppSession(
            config,
            KeystoreCredentialStore(this, "${config.baseUrl}|${config.oauthClientId}"),
            OkHttpTransport(),
            PendingSaveStore(KeystoreCredentialStore(this, "pending|${config.baseUrl}|${config.oauthClientId}")),
        )
        scope.launch { session.restore() }
    }
}
