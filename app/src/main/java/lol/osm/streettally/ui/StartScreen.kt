package lol.osm.streettally.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import lol.osm.streettally.AppConfig

/** Placeholder until sign-in and the campaign picker land: shows which backend this build uses. */
@Composable
fun StartScreen(config: AppConfig) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Street Tally", style = MaterialTheme.typography.headlineMedium)
            Text(config.baseUrl, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (config.writesAllowed) "Answers are sent to this server." else "Read only: answers are not sent.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview
@Composable
private fun StartScreenPreview() {
    StreetTallyTheme {
        StartScreen(
            AppConfig(
                baseUrl = "https://mr-stage.osm.lol",
                oauthClientId = "maproulette-ios-choice",
                callbackScheme = "lol.osm.maproulette.choice",
                allowLiveWrites = false,
                mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
                campaignLinkHost = "streettally.osm.lol",
            ),
        )
    }
}
