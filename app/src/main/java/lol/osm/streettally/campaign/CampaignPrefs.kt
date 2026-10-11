package lol.osm.streettally.campaign

import android.content.Context

/** Small per-device campaign state: the last campaign (reopened once per launch) and which
 * campaigns' welcome cards were seen. Nothing here leaves the phone. */
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

    private companion object {
        const val LAST = "lastCampaign"
        const val WELCOME = "welcomeSeen."
    }
}
