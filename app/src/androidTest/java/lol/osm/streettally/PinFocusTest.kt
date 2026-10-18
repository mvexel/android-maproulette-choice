package lol.osm.streettally

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import lol.osm.streettally.campaign.CampaignPrefs
import lol.osm.streettally.demo.DemoBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Accessibility focus on the map pins (A11y1 follow-up, Tess on #46: "focus jumps to Back after
 * activation"). With touch exploration on, as under TalkBack (ExploreService), a pin keeps the
 * focus when a double tap shows its card, Back from a place lands on its pin, and a zoomed-in cluster
 * hands the focus to a place or cluster on the map (iOS #63). Run by scripts/ci/emulator-instrumented.sh.
 */
class PinFocusTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Without this flag the test's own connection switches ExploreService off.
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val pinId = "${instrumentation.targetContext.packageName}:id/pin"
    private val clusterId = "${instrumentation.targetContext.packageName}:id/cluster"
    private val service = "${instrumentation.context.packageName}/${ExploreService::class.java.name}"

    @Before fun explore() {
        shell("settings put secure enabled_accessibility_services $service")
        shell("settings put secure accessibility_enabled 1")
        val manager = instrumentation.targetContext.getSystemService(AccessibilityManager::class.java)
        await("touch exploration") { manager.isTouchExplorationEnabled.takeIf { it } }
    }

    private fun openDemo(campaign: String, extras: Intent.() -> Unit = {}) {
        val intent = instrumentation.targetContext.packageManager
            .getLaunchIntentForPackage(instrumentation.targetContext.packageName)!!
            .putExtra("demo", campaign)
            .apply(extras)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // The welcome opens once every page is in (#64), after the tally and the first pins: one
        // opening mid-test takes the focus (main red on 5aff8d9). These tests are about the map, so
        // it counts as seen.
        val prefs = CampaignPrefs(instrumentation.targetContext)
        DemoBackend.IDS.values.forEach(prefs::markWelcomeSeen)
        instrumentation.targetContext.startActivity(intent)
        await("the campaign") { node { it.text?.startsWith("The tally:") == true } }
        await("the map") { mapNodes().firstOrNull() }
    }

    @After fun stopExploring() {
        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure accessibility_enabled 0")
    }

    @Test fun aPinKeepsTheFocusWhenItsCardShows() {
        openDemo("trees")
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

    @Test fun backFromAPlaceLandsOnItsPin() {
        openDemo("trees")
        val pin = await("an open pin") { pins().firstOrNull { !it.contentDescription.toString().endsWith(ALREADY_CHECKED) } }
        val said = pin.contentDescription.toString()
        assertTrue("the pin didn't take the focus", pin.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        assertTrue(pin.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("the place open") { Unit.takeIf { pins().isEmpty() } } // The map is gone while a place shows.
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        await("the focus back on the pin") { focused()?.takeIf { it.contentDescription?.toString() == said } }
    }

    @Test fun aZoomedInClusterHandsTheFocusToTheMap() {
        openDemo("large") { putExtra("places", 5_200); putExtra("latency", 0); putExtra("hang", false) }
        val cluster = await("a cluster") { mapNodes().firstOrNull { it.viewIdResourceName == clusterId } }
        val said = cluster.contentDescription.toString()
        assertTrue("the cluster didn't take the focus", cluster.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        await("the focus on the cluster") { focused()?.takeIf { it.contentDescription?.toString() == said } }
        assertTrue(cluster.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val landed = await("the focus on the zoomed-in map") {
            focused()?.takeIf { it.viewIdResourceName in setOf(pinId, clusterId) && it.contentDescription?.toString() != said }
        }
        assertNotEquals("the focus stayed on the zoomed cluster", said, landed.contentDescription?.toString())
    }

    private fun focused(): AccessibilityNodeInfo? = automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

    private fun pins(): List<AccessibilityNodeInfo> = all().filter { it.viewIdResourceName == pinId }

    private fun mapNodes(): List<AccessibilityNodeInfo> = all().filter { it.viewIdResourceName == pinId || it.viewIdResourceName == clusterId }

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
