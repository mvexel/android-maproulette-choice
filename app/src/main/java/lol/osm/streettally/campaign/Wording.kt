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
    fun guestStart(noun: Noun) = "No account needed to start. Answer a few ${noun.many} first, and we'll help you put them on the map afterwards."
    const val PUT_ON_MAP_INTRO = "To put them on the map, finish on a computer. We'll email you a link."
    const val EMAIL_USE = "We only email you about these answers, and delete your address once they're on the map or removed."
    const val EXISTING_ACCOUNT_HANDOFF = "Already have an OpenStreetMap account? Open the finish link on a computer and sign in there to put your saved answers on the map."
    fun day(date: java.time.Instant): String = java.time.format.DateTimeFormatter.ofPattern("MMM d")
        .withZone(java.time.ZoneId.systemDefault()).format(date)
    fun linkSent(email: String, until: java.time.Instant?) = "Sent to $email. Check your email later." +
        (until?.let { " The link works until ${day(it)}." } ?: "")
    fun savedNotOnMap(count: Int, noun: Noun, until: java.time.Instant) =
        "${noun.count(count)} saved, not on the map yet. Finish on a computer by ${day(until)}."
    const val SAVED_EXPIRED = "Your saved answers weren't put on the map, so we removed them. They still count in the campaign's totals. Thanks for checking."
    fun linkFailed(error: Throwable, noun: Noun): String {
        if (error is IllegalArgumentException) return "That doesn't look like an email address."
        val failure = error as? org.maproulette.sdk.MapRouletteException
        return when {
            failure?.reason == "nothing_saved" -> "Answer a ${noun.one} first, then we can send you the link."
            failure?.reason == "guest_claimed" -> "These saved answers have been claimed. Open your finish link on a computer to check their status."
            failure?.reason == "guest_expired" -> "This phone's saved-answer access has expired. Open your finish link on a computer to check their status."
            failure?.status == 400 && failure.reason == "invalid_request" -> "That doesn't look like an email address."
            failure?.kind == org.maproulette.sdk.ErrorKind.RATE_LIMIT -> "We've sent a few links already. Try again tomorrow."
            failure?.kind == org.maproulette.sdk.ErrorKind.NETWORK -> "Couldn't connect. Check your connection and try again."
            else -> "Couldn't send the link right now. Try again later. Your saved answers haven't been undone."
        }
    }
    const val SAVED_FOR_LATER = "Saved on our server. They go on the map when you finish on a computer."

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

    fun nearest(noun: Noun, distance: String?, hasOrigin: Boolean = true): String = if (!hasOrigin) "Choose a ${noun.one}" else "Nearest ${noun.one}" + (distance?.let { " · $it" } ?: "")
    fun progress(done: Int, total: Int, noun: Noun): String = "The tally: $done of ${noun.count(total)} checked"
    fun allChecked(noun: Noun): String = "All ${noun.many} are checked. Thank you!"
    fun locationStaysOnPhone(noun: Noun): String =
        "Your location stays on your phone. We use it to find the nearest ${noun.many}."

    const val ANSWERS_ARE_PUBLIC =
        "Your answers are saved to OpenStreetMap, the free map anyone can use and improve. Edits are public and show your OpenStreetMap username."

    // The place screen.
    const val NOT_SURE = "I'm not sure"
    const val NOT_SURE_SHORT = "Not sure"
    const val SOMETHING_WRONG = "Something's wrong here"
    const val DONE = "Done"
    fun cantCheck(noun: Noun): String = "I can't check this ${noun.one}"
    fun problemsMessage(noun: Noun): String =
        "Skip leaves it for someone else. “${cantCheck(noun)}” tells the organizer it needs another look."
    fun skip(noun: Noun): String = "Skip this ${noun.one}"
    fun alreadyChecked(noun: Noun): String = "Someone already checked this ${noun.one}. Thanks anyway!"
    fun cannotAnswer(noun: Noun): String = "This ${noun.one} can't be answered in the app."
    fun checkFailed(noun: Noun): String =
        "Couldn't check this ${noun.one} right now. Check your connection and try again."
    fun takenByOther(noun: Noun): String = "Someone else is checking this ${noun.one} right now. Try another one."
    fun notSureCloses(noun: Noun): String =
        "That's fine. The organizer can send someone back to ${noun.many} with open questions."
    fun nothingAnswered(noun: Noun): String =
        "You weren't sure about any question. You can skip this ${noun.one} instead."
    fun saved(todayCount: Int, noun: Noun): String = "Saved, thanks! That's ${noun.count(todayCount)} today."
    fun checkedByOtherFirst(noun: Noun): String =
        "Someone else checked this ${noun.one} first, so your answers weren't saved."
    fun next(noun: Noun, distance: String?, hasOrigin: Boolean = true): String = if (!hasOrigin) "Choose another ${noun.one}" else "Next nearest ${noun.one}" + (distance?.let { " · $it" } ?: "")
    fun lastOne(noun: Noun): String = "That was the last ${noun.one} to check. Thank you!"
    fun backToAll(noun: Noun): String = "Back to all ${noun.many}"
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
