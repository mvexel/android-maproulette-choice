package lol.osm.streettally

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import lol.osm.streettally.campaign.Wording
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a big campaign takes to open (L1, Tess's AE6b), on the demo build's [large][lol.osm.streettally.demo.DemoBackend.LARGE]
 * campaign: the trees survey copied to 5,200 and 4,999 places, every request held back [LATENCY_MS]
 * as on a slow connection. Measured from the launch: the first places on screen, and the full tally.
 * Frames come from `dumpsys gfxinfo`. Each run logs one `OpenTime` line, which the emulator job prints;
 * the test fails only when the tally never comes.
 */
class OpenTimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val pkg = instrumentation.targetContext.packageName

    @Test fun fiveThousandTwoHundredPlaces() = open(5_200)

    @Test fun fourThousandNineHundredNinetyNinePlaces() = open(4_999)

    /** One bar of signal (AE6b, AE7): every request hangs. The campaign shows the phone's copy, read
     * cold from its file, and Back shows the picker's kept entry, each about SLOW_MS after opening. */
    @Test fun aDeadSpotShowsTheKeptCopy() {
        open(5_200)
        SystemClock.sleep(5_000) // The copy is saved after the tally shows.
        val start = SystemClock.uptimeMillis()
        instrumentation.targetContext.startActivity(launch(5_200).putExtra("hang", true).putExtra("cold", true))
        val campaign = poll(BUDGET_MS) { texts().firstOrNull { it.startsWith(KEPT_COPY) } }?.let { SystemClock.uptimeMillis() - start }
        node { it.text?.toString() == LETS_GO }?.let(::click)
        val back = SystemClock.uptimeMillis()
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        val picker = poll(BUDGET_MS) { texts().firstOrNull { it == Wording.PICKER_KEPT } }?.let { SystemClock.uptimeMillis() - back }
        Log.i(TAG, "dead spot, 5200 places: kept copy on the campaign ${campaign ?: "never"} ms (cold), " +
            "kept entry on the picker ${picker ?: "never"} ms after Back")
        instrumentation.targetContext.startActivity(launch(5_200).putExtra("hang", false))
        assertTrue("no kept copy on the campaign", campaign != null)
        assertTrue("no kept entry on the picker", picker != null)
    }

    private fun launch(places: Int): Intent =
        instrumentation.targetContext.packageManager.getLaunchIntentForPackage(pkg)!!
            .putExtra("places", places)
            .putExtra("latency", LATENCY_MS)
            .putExtra("hang", false)
            .putExtra("demo", "large")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun <T : Any> poll(timeoutMs: Long, find: () -> T?): T? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            find()?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        return find()
    }

    private fun open(places: Int) {
        shell("dumpsys gfxinfo $pkg reset")
        val intent = launch(places)
        val start = SystemClock.uptimeMillis()
        instrumentation.targetContext.startActivity(intent)
        var first: Long? = null
        var soFar: Long? = null
        var welcome: Long? = null
        var tally: Long? = null
        val deadline = start + BUDGET_MS
        while (tally == null && SystemClock.uptimeMillis() < deadline) {
            val texts = texts()
            val now = SystemClock.uptimeMillis() - start
            if (first == null && texts.any { it.startsWith("Loading ") && it.endsWith(" so far") || it.startsWith("The tally:") || it == LETS_GO }) first = now
            if (soFar == null && texts.any { it.startsWith("Loading ") && it.endsWith(" so far") }) soFar = now
            if (texts.contains(LETS_GO)) {
                // The welcome covers the tally: close it, as a person would.
                if (welcome == null) welcome = now
                node { it.text?.toString() == LETS_GO }?.let(::click)
            }
            if (texts.any { it.startsWith("The tally:") }) tally = now
            if (tally == null) SystemClock.sleep(POLL_MS)
        }
        val frames = shell("dumpsys gfxinfo $pkg")
        val total = Regex("Total frames rendered: (\\d+)").find(frames)?.groupValues?.get(1)
        val janky = Regex("Janky frames: (\\d+ \\([0-9.]+%\\))").find(frames)?.groupValues?.get(1)
        Log.i(TAG, "$places places: first on screen ${first ?: "-"} ms, loading line ${soFar ?: "never"}, welcome ${welcome ?: "-"} ms, " +
            "tally ${tally ?: "-"} ms; frames $total, janky $janky (latency $LATENCY_MS ms a request)")
        assertTrue("no tally for $places places within ${BUDGET_MS / 1000} s", tally != null)
    }

    private fun shell(command: String): String =
        automation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

    private fun texts(): List<String> = all().mapNotNull { it.text?.toString() }

    private fun node(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? = all().firstOrNull(match)

    private fun all(): List<AccessibilityNodeInfo> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return out
    }

    /** Clicks [node], or its nearest clickable ancestor (a Compose button's text isn't clickable itself). */
    private fun click(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private companion object {
        const val TAG = "OpenTime"
        const val LETS_GO = "Let's go"
        /** The start of [Wording.keptCopy]. */
        const val KEPT_COPY = "No signal. Showing the list from"
        /** A slow phone connection's round trip. */
        const val LATENCY_MS = 300
        const val POLL_MS = 100L
        const val BUDGET_MS = 180_000L
    }
}
