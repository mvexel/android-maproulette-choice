package lol.osm.streettally

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Does nothing but turn on touch exploration, as TalkBack does (TalkBack itself can't be driven from adb). */
class ExploreService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
