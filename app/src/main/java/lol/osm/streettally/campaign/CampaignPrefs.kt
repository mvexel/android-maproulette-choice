package lol.osm.streettally.campaign

import android.content.Context

/** Small per-device campaign state: the last campaign (reopened once per launch), which
 * campaigns' welcome cards were seen, and how many places were checked per campaign per day.
 * Nothing here leaves the phone. */
class CampaignPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("campaigns", Context.MODE_PRIVATE)

    var lastCampaign: Long?
        get() = prefs.getLong(LAST, 0).takeIf { it > 0 }
        set(value) {
            prefs.edit().apply { if (value == null) remove(LAST) else putLong(LAST, value) }.apply()
        }

    fun hasSeenWelcome(campaign: Long): Boolean = prefs.getBoolean("$WELCOME$campaign", false)

    fun markWelcomeSeen(campaign: Long) {
        prefs.edit().putBoolean("$WELCOME$campaign", true).apply()
    }

    /** True once per install: the first saved guest answer opens the "put them on the map" card. */
    fun takePutOnMapCard(): Boolean {
        if (prefs.getBoolean(PUT_ON_MAP_SHOWN, false)) return false
        prefs.edit().putBoolean(PUT_ON_MAP_SHOWN, true).apply()
        return true
    }

    /** The checked-today counts ([CheckedLog]), so "You've checked 3 today" survives a restart. */
    val checkedCounts: CheckedLog.Counts = object : CheckedLog.Counts {
        override fun get(key: String) = prefs.getInt(key, 0)
        override fun set(key: String, value: Int) {
            prefs.edit().putInt(key, value).apply()
        }
    }

    private companion object {
        const val LAST = "lastCampaign"
        const val WELCOME = "welcomeSeen."
        const val PUT_ON_MAP_SHOWN = "putOnMapCardShown"
    }
}
