package lol.osm.streettally.campaign

import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.templateProperties
import org.maproulette.sdk.work

/** What a campaign's tasks are called on screen ("stop", "bench"), from the identity tag the
 * challenge's payload guards on (`match`). Falls back to "place". Same table as the iOS app. */
data class Noun(val one: String, val many: String) {
    /** "1 stop", "7 stops". */
    fun count(n: Int): String = "$n ${if (n == 1) one else many}"
    val capitalized: String get() = one.replaceFirstChar { it.uppercase() }

    companion object {
        val PLACE = Noun("place", "places")

        private val known = listOf(
            Triple("highway", "bus_stop", Noun("stop", "stops")),
            Triple("public_transport", "platform", Noun("stop", "stops")),
            Triple("railway", "platform", Noun("platform", "platforms")),
            Triple("amenity", "bench", Noun("bench", "benches")),
            Triple("amenity", "bicycle_parking", Noun("bike rack", "bike racks")),
            Triple("amenity", "waste_basket", Noun("trash can", "trash cans")),
            Triple("amenity", "drinking_water", Noun("water fountain", "water fountains")),
            Triple("amenity", "toilets", Noun("restroom", "restrooms")),
            Triple("amenity", "shelter", Noun("shelter", "shelters")),
            Triple("leisure", "playground", Noun("playground", "playgrounds")),
            Triple("leisure", "park", Noun("park", "parks")),
        )

        fun from(match: Map<String, String>): Noun =
            known.firstOrNull { (key, value, _) -> match[key] == value }?.third ?: PLACE

        /** The noun for a campaign: from the first choice task's identity guard. */
        fun from(tasks: List<Task>): Noun {
            for (task in tasks) {
                val work = task.work()
                if (work is TaskWork.Choice) return from(work.match)
            }
            return PLACE
        }
    }
}

/** Participant-facing copy, as in the iOS app's `Wording`. */
object Wording {
    const val NEEDS_ACCOUNT = "To save answers you'll sign in with a free OpenStreetMap account."
    const val NO_CAMPAIGNS =
        "No campaigns here yet. Got a campaign link or QR code from your organizer? Open it to jump straight in."

    /** A place's display name: its `name`, else "Stop 12345" from `ref`, else "Stop". */
    fun placeName(task: Task, noun: Noun): String {
        val properties = task.templateProperties()
        properties["name"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        properties["ref"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return "${noun.capitalized} $it" }
        return noun.capitalized
    }

    fun nearest(noun: Noun, distance: String?): String = "Nearest ${noun.one}" + (distance?.let { " · $it" } ?: "")
    fun progress(done: Int, total: Int, noun: Noun): String = "The tally: $done of ${noun.count(total)} checked"
    fun allChecked(noun: Noun): String = "All ${noun.many} are checked. Thank you!"
    fun locationStaysOnPhone(noun: Noun): String =
        "Your location stays on your phone. We use it to find the nearest ${noun.many}."
}

/** Plain words for a failed read, as in the iOS picker. */
fun errorMessage(error: Throwable): String {
    val kind = (error as? org.maproulette.sdk.MapRouletteException)?.kind
        ?: return "Couldn't load. Please try again."
    return when (kind) {
        org.maproulette.sdk.ErrorKind.NOT_FOUND -> "This campaign couldn't be found. It may have ended."
        org.maproulette.sdk.ErrorKind.AUTHENTICATION, org.maproulette.sdk.ErrorKind.PERMISSION ->
            "Please sign in again to do this."
        org.maproulette.sdk.ErrorKind.NETWORK -> "Couldn't connect. Check your connection and try again."
        org.maproulette.sdk.ErrorKind.RATE_LIMIT -> "Too many requests right now. Wait a moment and try again."
        else -> "Something went wrong on our side. Please try again."
    }
}
