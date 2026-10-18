package lol.osm.streettally

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Switching to dark mode on a 5,200-place campaign, zoomed out over its clusters (Tess, AE8): the
 * recreated activity once waited seconds on the old map's renderer teardown and stopped responding.
 * The activity now keeps running and the map restyles in place; it should still show the clusters
 * (they only exist up to zoom 15).
 * scripts/ci/emulator-instrumented.sh fails the run on any "ANR in" line in logcat, since CI hides the dialog.
 */
class DarkSwitchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val pkg = instrumentation.targetContext.packageName
    private val clusterId = "$pkg:id/cluster"

    @After fun backToLight() {
        shell("cmd uimode night no")
    }

    @Test fun switchingToDarkKeepsTheClustersAndResponds() {
        shell("cmd uimode night no")
        instrumentation.targetContext.startActivity(
            instrumentation.targetContext.packageManager.getLaunchIntentForPackage(pkg)!!
                .putExtra("demo", "large")
                .putExtra("places", 5_200)
                .putExtra("latency", 0)
                .putExtra("hang", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        await("the tally") {
            texts().firstOrNull { it == "Let's go" }?.let { node { n -> n.text?.toString() == it }?.let(::click) }
            texts().firstOrNull { it.startsWith("The tally:") }
        }
        val before = await("clusters") { clusters().takeIf { it.isNotEmpty() } }.size
        val start = SystemClock.uptimeMillis()
        shell("cmd uimode night yes")
        // Give the restyle a moment to begin before looking for the clusters again.
        SystemClock.sleep(1_000)
        val after = await("clusters after the switch") { clusters().takeIf { it.isNotEmpty() } }.size
        val back = SystemClock.uptimeMillis() - start
        // A few seconds zoomed out on the dark map, where the ANR used to show.
        SystemClock.sleep(6_000)
        Log.i(TAG, "dark switch, 5200 places: $before clusters before, $after after, back in $back ms")
        assertTrue("the map took $back ms to come back", back < BACK_MS)
    }

    private fun clusters(): List<AccessibilityNodeInfo> = all().filter { it.viewIdResourceName == clusterId }

    private fun texts(): List<String> = all().mapNotNull { it.text?.toString() }

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

    private fun shell(command: String) {
        automation.executeShellCommand(command).close()
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
        const val TAG = "DarkSwitch"
        const val BUDGET_MS = 60_000L
        /** From the switch to clusters on the dark map. */
        const val BACK_MS = 20_000L
    }
}
