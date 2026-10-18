package lol.osm.streettally

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Accessibility focus on the map pins (A11y1 follow-up, Tess on #46: "focus jumps to Back after
 * activation"). With touch exploration on, as under TalkBack (ExploreService), a pin keeps the
 * focus when a double tap shows its card. Run by scripts/ci/emulator-instrumented.sh on the demo trees.
 */
class PinFocusTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Without this flag the test's own connection switches ExploreService off.
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val pinId = "${instrumentation.targetContext.packageName}:id/pin"
    private val service = "${instrumentation.context.packageName}/${ExploreService::class.java.name}"

    @Before fun exploreAndOpenTrees() {
        shell("settings put secure enabled_accessibility_services $service")
        shell("settings put secure accessibility_enabled 1")
        val manager = instrumentation.targetContext.getSystemService(AccessibilityManager::class.java)
        await("touch exploration") { manager.isTouchExplorationEnabled.takeIf { it } }
        val intent = instrumentation.targetContext.packageManager
            .getLaunchIntentForPackage(instrumentation.targetContext.packageName)!!
            .putExtra("demo", "trees")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.targetContext.startActivity(intent)
        await("the campaign") { node { it.text?.toString() == "Let's go" } ?: pins().firstOrNull() }
        node { it.text?.toString() == "Let's go" }?.let(::click)
        await("pins") { pins().firstOrNull() }
    }

    @After fun stopExploring() {
        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure accessibility_enabled 0")
    }

    @Test fun aPinKeepsTheFocusWhenItsCardShows() {
        val pin = await("a checked pin") { pins().firstOrNull { it.contentDescription.toString().endsWith(ALREADY_CHECKED) } }
        val said = pin.contentDescription.toString()
        assertTrue("the pin didn't take the focus", pin.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        await("the focus on the pin") { focused()?.takeIf { it.contentDescription?.toString() == said } }
        assertTrue(pin.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("its card") {
            node { it.liveRegion != View.ACCESSIBILITY_LIVE_REGION_NONE && subtree(it).any { n -> n.text?.contains(ALREADY_CHECKED) == true } }
        }
        SystemClock.sleep(3_000) // Long enough for the map or the screen to take the focus, were it going to.
        assertEquals("the focus left the pin", said, focused()?.contentDescription?.toString())
    }

    private fun focused(): AccessibilityNodeInfo? = automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

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

    private companion object {
        const val ALREADY_CHECKED = "Already checked"
    }
}
