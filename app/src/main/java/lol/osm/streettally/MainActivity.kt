package lol.osm.streettally

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import lol.osm.streettally.ui.StartScreen
import lol.osm.streettally.ui.StreetTallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val config = AppConfig.fromBuild()
        setContent {
            StreetTallyTheme {
                StartScreen(config)
            }
        }
    }
}
