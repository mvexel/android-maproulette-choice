package lol.osm.streettally.campaign

import android.content.Context
import lol.osm.streettally.task.GuestOutbox

/** Small per-device campaign state: the last campaign (reopened once per launch), which
 * campaigns' welcome cards were seen, how many places were checked per campaign per day, and a
 * guest's answers still waiting to be sent. Only those answers ever leave the phone, once sent. */
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

    /** The checked-today counts ([CheckedLog]), so "You've checked 3 today" survives a restart. */
    val checkedCounts: CheckedLog.Counts = object : CheckedLog.Counts {
        override fun get(key: String) = prefs.getInt(key, 0)
        override fun set(key: String, value: Int) {
            prefs.edit().putInt(key, value).apply()
        }
    }

    /** A guest's answers waiting to be sent ([GuestOutbox]); they stay on the phone until then. */
    val outboxStore: GuestOutbox.Store = object : GuestOutbox.Store {
        override fun read(): String? = prefs.getString(OUTBOX, null)
        override fun write(value: String?) {
            prefs.edit().apply { if (value == null) remove(OUTBOX) else putString(OUTBOX, value) }.apply()
        }
    }

    private companion object {
        const val OUTBOX = "guestOutbox"
        const val LAST = "lastCampaign"
        const val WELCOME = "welcomeSeen."
    }
}
