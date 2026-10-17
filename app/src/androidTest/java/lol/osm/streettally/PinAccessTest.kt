package lol.osm.streettally

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import lol.osm.streettally.campaign.Wording
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The map pins' TalkBack nodes (A11y1), driven by the accessibility action TalkBack's double-tap
 * sends, on the demo build's trees (1090 and 1094 are checked there). Run by the emulator smoke.
 * TalkBack itself can't be driven from adb (Tess, #47), so swiping and announcements still need a person.
 */
class PinAccessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val pinId = "${instrumentation.targetContext.packageName}:id/pin"
    private val states = listOf(Wording.NEEDS_LOOK, Wording.WAITING_TO_SEND, Wording.SAVED_NOT_ON_MAP, ALREADY_CHECKED)

    @Before fun openTrees() {
        val intent = instrumentation.targetContext.packageManager
            .getLaunchIntentForPackage(instrumentation.targetContext.packageName)!!
            .putExtra("demo", "trees")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.targetContext.startActivity(intent)
        await("the campaign") { node { it.text?.toString() == "Let's go" } ?: pins().firstOrNull() }
        node { it.text?.toString() == "Let's go" }?.let { click(it) }
        await("pins") { pins().firstOrNull() }
    }

    @Test fun doubleTappingAnOpenPinOpensItsPlace() {
        val open = pins().filter { pin -> states.none { pin.contentDescription.toString().endsWith(it) } }
        assertTrue("no open pin on screen", open.isNotEmpty())
        // Another guest's place has no state either but shows its card: try a few.
        val opened = open.take(3).any { pin ->
            assertTrue(pin.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val question = poll(10_000) { node { it.text?.startsWith("Question ") == true } }
            if (question == null) automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            question != null
        }
        assertTrue("double-tapping an open pin didn't open its place", opened)
    }

    @Test fun doubleTappingACheckedPinShowsItsCardAndReadsItOut() {
        val checked = await("a checked pin") { pins().firstOrNull { it.contentDescription.toString().endsWith(ALREADY_CHECKED) } }
        assertTrue(checked.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val card = await("its card") {
            node { it.liveRegion != View.ACCESSIBILITY_LIVE_REGION_NONE && subtree(it).any { n -> n.text?.contains(ALREADY_CHECKED) == true } }
        }
        assertNotNull(card)
        SystemClock.sleep(2_000) // Long enough for a place to open, were it going to.
        assertNull("a checked pin opened its place", node { it.text?.startsWith("Question ") == true })
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

    /** Clicks [node], or its nearest clickable ancestor (a Compose button's text isn't clickable itself). */
    private fun click(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        assertTrue((n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun <T : Any> poll(timeoutMs: Long, find: () -> T?): T? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            find()?.let { return it }
            SystemClock.sleep(250)
        }
        return find()
    }

    private fun <T : Any> await(what: String, find: () -> T?): T =
        poll(30_000, find) ?: throw AssertionError("no $what within 30 s")

    private companion object {
        const val ALREADY_CHECKED = "Already checked"
    }
}
