package lol.osm.streettally

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import lol.osm.streettally.auth.BrowserSignIn
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.ui.CampaignScreen
import lol.osm.streettally.ui.PickerScreen
import lol.osm.streettally.ui.StreetTallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as StreetTallyApp
        val privateSignIn = BrowserSignIn.privateTabsSupported(this)
        setContent {
            StreetTallyTheme {
                // Two screens for now: the picker and one campaign. Answering a place comes next.
                var campaignId by rememberSaveable { mutableStateOf<Long?>(null) }
                var placeTitle by remember { mutableStateOf<String?>(null) }
                val id = campaignId
                if (id == null) {
                    PickerScreen(
                        session = app.session,
                        appScope = app.scope,
                        privateSignIn = privateSignIn,
                        authenticate = { url -> BrowserSignIn.authenticate(this, url) },
                        onCampaign = { campaignId = it },
                    )
                } else {
                    BackHandler { campaignId = null }
                    CampaignScreen(
                        session = app.session,
                        campaignId = id,
                        onBack = { campaignId = null },
                        onPlace = { task, places -> placeTitle = Wording.placeName(task.task, places.noun) },
                    )
                }
                placeTitle?.let { title ->
                    AlertDialog(
                        onDismissRequest = { placeTitle = null },
                        title = { Text(title) },
                        text = { Text("Answering this place comes in the next update.") },
                        confirmButton = { TextButton(onClick = { placeTitle = null }) { Text("OK") } },
                    )
                }
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
