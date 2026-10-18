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
 * Frames come from `dumpsys gfxinfo`, memory from `dumpsys meminfo` sampled about once a second while
 * opening (the test runs in the app's process, so that includes a little of its own). Each open logs one `OpenTime` line, which the emulator job prints, and 5,200 places open
 * three times for a median (L1 targets: tally within 30 s, peak under 300 MB); the test fails only when
 * the tally never comes.
 */
class OpenTimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val pkg = instrumentation.targetContext.packageName

    /** Three opens, so one slow emulator moment doesn't decide it (Ada): the first with the welcome. */
    @Test fun fiveThousandTwoHundredPlaces() {
        val opens = List(3) { open(5_200) }
        val tallies = opens.map { it.tallyMs }.sorted()
        Log.i(TAG, "5200 places, median of 3: tally ${tallies[1]} ms (${opens.joinToString { "${it.tallyMs}" }}); " +
            "peak PSS ${opens.maxOf { it.pssKb } / 1024} MB, RSS ${opens.maxOf { it.rssKb } / 1024} MB")
    }

    @Test fun fourThousandNineHundredNinetyNinePlaces() { open(4_999) }

    /** The open a participant sees first: cleared data, so no kept copy, and the welcome. The emulator
     * job runs only this, three times after `pm clear`, and prints the median (Ada); the class run skips it. */
    @Test fun coldOpen() {
        val opened = open(5_200)
        Log.i(TAG, "cold 5200 places: tally ${opened.tallyMs} ms, peak PSS ${opened.pssKb / 1024} MB, RSS ${opened.rssKb / 1024} MB")
    }

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

    private class Opened(val tallyMs: Long, val pssKb: Long, val rssKb: Long)

    private fun open(places: Int): Opened {
        // From Home, so the last open's tally still on screen can't count as this one's (it read 33 ms on 1eb9f84).
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        poll(5_000) { if (texts().none { it.startsWith("The tally:") }) true else null }
        shell("dumpsys gfxinfo $pkg reset")
        val intent = launch(places)
        val start = SystemClock.uptimeMillis()
        instrumentation.targetContext.startActivity(intent)
        var first: Long? = null
        var soFar: Long? = null
        var welcome: Long? = null
        var tally: Long? = null
        var pss = 0L
        var rss = 0L
        var sampled = 0L
        val deadline = start + BUDGET_MS
        while (tally == null && SystemClock.uptimeMillis() < deadline) {
            val texts = texts()
            val now = SystemClock.uptimeMillis() - start
            if (now - sampled >= MEMORY_EVERY_MS) {
                sampled = now
                memory().let { (p, r) -> pss = maxOf(pss, p); rss = maxOf(rss, r) }
            }
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
        memory().let { (p, r) -> pss = maxOf(pss, p); rss = maxOf(rss, r) }
        val frames = shell("dumpsys gfxinfo $pkg")
        val total = Regex("Total frames rendered: (\\d+)").find(frames)?.groupValues?.get(1)
        val janky = Regex("Janky frames: (\\d+ \\([0-9.]+%\\))").find(frames)?.groupValues?.get(1)
        Log.i(TAG, "$places places: first on screen ${first ?: "-"} ms, loading line ${soFar ?: "never"}, welcome ${welcome ?: "-"} ms, " +
            "tally ${tally ?: "-"} ms; frames $total, janky $janky; peak PSS ${pss / 1024} MB, RSS ${rss / 1024} MB " +
            "(latency $LATENCY_MS ms a request)")
        // When it never comes, say what was on screen instead (another app's dialog, a stuck welcome).
        assertTrue("no tally for $places places within ${BUDGET_MS / 1000} s; on screen: ${screen()}", tally != null)
        return Opened(tally!!, pss, rss)
    }

    /** The app's total PSS and RSS in kB, or zeros when `dumpsys meminfo` doesn't say. */
    private fun memory(): Pair<Long, Long> {
        val info = shell("dumpsys meminfo $pkg")
        fun total(name: String) = Regex("TOTAL $name:\\s+(\\d+)").find(info)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return total("PSS") to total("RSS")
    }

    private fun shell(command: String): String =
        automation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

    private fun texts(): List<String> = all().mapNotNull { it.text?.toString() }

    private fun screen(): String {
        val root = automation.rootInActiveWindow ?: return "no window"
        return "${root.packageName}: " + texts().take(8).joinToString(" | ")
    }

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
        const val MEMORY_EVERY_MS = 1_000L
        const val BUDGET_MS = 180_000L
    }
}
