package lol.osm.streettally

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TalkBack on a clustered map (A11y1 follow-up after #51/#52): the demo's 5,200 places open zoomed
 * out over clusters, and each cluster must still be a node that says how many places it holds and
 * zooms in on a double tap. Run by scripts/ci/emulator-instrumented.sh.
 */
class ClusterAccessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val pkg = instrumentation.targetContext.packageName
    private val clusterId = "$pkg:id/cluster"
    private val pinId = "$pkg:id/pin"

    @Before fun openLarge() {
        instrumentation.targetContext.startActivity(
            instrumentation.targetContext.packageManager.getLaunchIntentForPackage(pkg)!!
                .putExtra("demo", "large")
                .putExtra("places", 5_200)
                .putExtra("latency", 0)
                .putExtra("hang", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        await("the tally") {
            node { it.text?.toString() == "Let's go" }?.let(::click)
            node { it.text?.startsWith("The tally:") == true }
        }
    }

    @Test fun clustersSayHowManyPlacesTheyHold() {
        val clusters = await("clusters") { clusters().takeIf { it.isNotEmpty() } }
        for (cluster in clusters) {
            val said = cluster.contentDescription?.toString().orEmpty()
            val count = SAYS.find(said)?.groupValues?.get(1)?.filter(Char::isDigit)?.toIntOrNull()
            assertTrue("a cluster says \"$said\"", count != null && count > 1)
            assertTrue("a cluster isn't clickable: \"$said\"", cluster.isClickable)
        }
    }

    @Test fun doubleTappingAClusterZoomsIn() {
        val before = await("clusters") { clusters().takeIf { it.isNotEmpty() } }
        val shown = reached()
        assertTrue(before.first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("the map zoomed in") { reached().takeIf { it.isNotEmpty() && it != shown } }
    }

    private fun clusters(): List<AccessibilityNodeInfo> = all().filter { it.viewIdResourceName == clusterId }

    /** What TalkBack can reach on the map now: every cluster and pin, by what it says. */
    private fun reached(): Set<String> =
        all().filter { it.viewIdResourceName == clusterId || it.viewIdResourceName == pinId }
            .mapTo(HashSet()) { it.contentDescription?.toString().orEmpty() }

    private fun node(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? = all().firstOrNull(match)

    private fun all(): List<AccessibilityNodeInfo> = automation.rootInActiveWindow?.let(::subtree).orEmpty()

    private fun subtree(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return out
    }

    private fun click(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun <T : Any> await(what: String, find: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + BUDGET_MS
        while (SystemClock.uptimeMillis() < deadline) {
            find()?.let { return it }
            SystemClock.sleep(250)
        }
        return find() ?: throw AssertionError("no $what within $BUDGET_MS ms")
    }

    private companion object {
        const val BUDGET_MS = 60_000L
        /** Wording.cluster: "1,234 trees left to check here" or "56 trees here, all checked". */
        val SAYS = Regex("""^([\d,.  ]+) .+ (left to check here|here, all checked)$""")
    }
}
