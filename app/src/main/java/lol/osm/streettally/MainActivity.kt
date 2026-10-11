package lol.osm.streettally

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import lol.osm.streettally.auth.BrowserSignIn
import lol.osm.streettally.ui.StartScreen
import lol.osm.streettally.ui.StreetTallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as StreetTallyApp
        val privateSignIn = BrowserSignIn.privateTabsSupported(this)
        setContent {
            StreetTallyTheme {
                StartScreen(
                    session = app.session,
                    scope = app.scope,
                    privateSignIn = privateSignIn,
                    authenticate = { url -> BrowserSignIn.authenticate(this, url) },
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        BrowserSignIn.onHostPaused()
    }

    override fun onResume() {
        super.onResume()
        BrowserSignIn.onHostResumed()
    }
}
