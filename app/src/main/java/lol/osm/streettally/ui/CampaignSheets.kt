package lol.osm.streettally.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.Wording
import org.maproulette.sdk.ChoiceQuestion

/** What the campaign is about, what to look for, and what happens to answers and location.
 * Shown on the first visit before the location prompt, and from "About this campaign". */
@Composable
fun WelcomeDialog(
    name: String,
    description: String?,
    openCount: Int,
    questions: List<ChoiceQuestion>,
    noun: Noun,
    signedIn: Boolean,
    onClose: () -> Unit,
    /** In preview: "You're previewing as …", in place of the account line. */
    previewingAs: String? = null,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                description?.trim()?.takeIf { it.isNotEmpty() }?.let { Text(it) }
                if (openCount > 0) Text("${noun.count(openCount)} to check. Each takes about a minute.")
                if (questions.isNotEmpty()) {
                    Text("What to look for", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    questions.forEach { q ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("• ${q.prompt}")
                            q.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text(Wording.ANSWERS_ARE_PUBLIC, style = MaterialTheme.typography.bodySmall)
                Text(Wording.locationStaysOnPhone(noun), style = MaterialTheme.typography.bodySmall)
                if (previewingAs != null) Text(Wording.previewingAs(previewingAs), style = MaterialTheme.typography.bodySmall)
                else if (!signedIn) Text(Wording.NEEDS_ACCOUNT, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Let's go") }
        },
    )
}

/** A QR code and a share button for the campaign's web link. */
@Composable
fun ShareDialog(name: String, link: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val qr = remember(link) { qrCode(link) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(name) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Scan to open this campaign in the app", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR code for $name",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 280.dp),
                )
                Text(link, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text("Share link") }
                Text(
                    "People need the app installed for the link to open it.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

/** A crisp black-on-white QR code (error correction M, one pixel per module plus a quiet zone). */
internal fun qrCode(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
    )
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
