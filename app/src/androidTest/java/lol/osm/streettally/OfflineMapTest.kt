package lol.osm.streettally

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The map without its style (#58's first CI run: every map test found no pins). With the network
 * off and nothing cached, the style can't load; the places still go on the map, on a plain
 * background, so they can be seen and TalkBack reaches them. The demo backend is in the app, so
 * the places themselves load offline. Run last by scripts/ci/emulator-instrumented.sh.
 */
class OfflineMapTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val pinId = "${instrumentation.targetContext.packageName}:id/pin"

    @Before fun offline() {
        shell("svc wifi disable")
        shell("svc data disable")
    }

    @After fun online() {
        shell("svc wifi enable")
        shell("svc data enable")
    }

    @Test fun placesShowWhenTheMapStyleCantLoad() {
        instrumentation.targetContext.startActivity(
            instrumentation.targetContext.packageManager
                .getLaunchIntentForPackage(instrumentation.targetContext.packageName)!!
                .putExtra("demo", "trees")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        await("the campaign") { node { it.text?.toString() == "Let's go" } ?: pins().firstOrNull() }
        node { it.text?.toString() == "Let's go" }?.let(::click)
        val pins = await("pins on the map") { pins().takeIf { it.isNotEmpty() } }
        assertTrue(pins.all { it.isClickable })
    }

    private fun pins(): List<AccessibilityNodeInfo> = all().filter { it.viewIdResourceName == pinId }

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
        assertTrue((n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun shell(command: String) {
        automation.executeShellCommand(command).close()
    }

    private fun <T : Any> await(what: String, find: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            find()?.let { return it }
            SystemClock.sleep(250)
        }
        return find() ?: throw AssertionError("no $what within 30 s")
    }
}
