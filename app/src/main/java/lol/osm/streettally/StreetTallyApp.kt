package lol.osm.streettally

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.auth.KeystoreCredentialStore
import org.maproulette.sdk.OkHttpTransport

/** Holds the process-wide session, so sign-in survives activity recreation. */
class StreetTallyApp : Application() {
    val scope: CoroutineScope = MainScope()
    lateinit var session: AppSession
        private set

    override fun onCreate() {
        super.onCreate()
        val config = AppConfig.fromBuild()
        session = AppSession(
            config,
            KeystoreCredentialStore(this, "${config.baseUrl}|${config.oauthClientId}"),
            OkHttpTransport(),
        )
        scope.launch { session.restore() }
    }
}
