package lol.osm.streettally.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import lol.osm.streettally.campaign.Wording

/** Brand navy and amber (brand/colors.json `textPrimary` and `accent`), the live page's signal colors. */
private val NAVY = Color(0xFF14213D)
private val AMBER = Color(0xFFF5A524)

/** Above every screen of a previewed campaign (design/preview-mode.md §4.1): amber on navy, full
 * width and not dismissible. With [published] (a refresh found the campaign published, §5) it
 * offers to open it for real instead of Exit. */
@Composable
fun PreviewBanner(published: Boolean, onExit: () -> Unit, onOpenForReal: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(NAVY).padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                if (published) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(Wording.PREVIEW_NOW_PUBLISHED) }
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(Wording.PREVIEW_BANNER_TITLE) }
                    append(" ")
                    append(Wording.PREVIEW_BANNER_TEXT)
                }
            },
            color = AMBER,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = if (published) onOpenForReal else onExit, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(if (published) Wording.OPEN_FOR_REAL else Wording.EXIT_PREVIEW, color = AMBER, fontWeight = FontWeight.SemiBold)
        }
    }
}
