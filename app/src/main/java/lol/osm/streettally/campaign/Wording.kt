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

    /** Guests' answers reach OSM only after they finish signing up, under the username they pick then. */
    const val GUEST_ANSWERS_ARE_PUBLIC =
        "Your answers go on OpenStreetMap, the free map anyone can use, when you finish on a computer. Map edits are public and show the username you choose then."
    const val ANSWERS_ARE_PUBLIC =
        "Your answers are saved to OpenStreetMap, the free map anyone can use and improve. Edits are public and show your OpenStreetMap username."

    // The place screen.
    const val NOT_SURE = "I'm not sure"
    const val NOT_SURE_SHORT = "Not sure"
    const val SOMETHING_WRONG = "Something's wrong here"
    const val DONE = "Done"
    fun cantCheck(noun: Noun): String = "I can't check this ${noun.one}"

    // "Something's wrong here" (design/not-here-moved.md §2, §7), as in the iOS app.
    const val SKIP_EXPLAINED = "Leave it for someone else."
    const val CANT_CHECK_EXPLAINED = "You're near it but can't see or reach it."
    const val NOT_HERE = "It's not here"
    fun notHereExplained(noun: Noun): String = "You're at the spot and there's no ${noun.one}."
    const val MOVED = "It's moved"
    const val MOVED_EXPLAINED = "You can see it somewhere else nearby. You'll show where."
    const val PROBLEMS_MESSAGE = "Nothing here changes OpenStreetMap. The organizer checks every report."

    fun movedTitle(noun: Noun): String = "Where is the ${noun.one} now?"
    const val MOVED_HELP = "Tap the map where it is, or stand next to it and use your location."
    const val STANDING_AT_IT = "I'm standing at it"
    const val WAITING_FOR_FIX = "Waiting for a better location. Or tap the map."
    const val ON_THE_MAP = "On the map"
    const val SAVE_THIS_SPOT = "Save this spot"
    fun movedDistance(meters: Double): String = "About ${MovedSpot.roundedMeters(meters)} m from where the map has it."
    const val MOVED_TOO_CLOSE =
        "That's where the map already has it. If it's there, go back and answer the questions instead."
    fun movedFar(meters: Double, noun: Noun): String =
        "That's ${MovedSpot.roundedMeters(meters)} m away. If it's a different ${noun.one}, use “$NOT_HERE” instead."
    fun movedTooFar(noun: Noun): String = "That's too far to be the same ${noun.one}."
    const val SPOT_ONLY = "Only the spot you pick is saved, not your own location."
    fun spotIsHere(noun: Noun): String = "Your current location is saved as the ${noun.one}'s new spot."

    const val CHECK_REPORT = "Check your report"
    const val REPORTING = "You're reporting:"
    fun reportNotHere(noun: Noun): String = "The ${noun.one} isn't here."
    fun reportMoved(meters: Double, noun: Noun): String =
        "The ${noun.one} has moved, about ${MovedSpot.roundedMeters(meters)} m from where the map has it."
    const val REPORT_NOT_HERE_NOTE = "Nothing changes on OpenStreetMap. The organizer will check it."
    const val REPORT_MOVED_NOTE = "Nothing changes on OpenStreetMap. The organizer will move it on the map."
    const val REPORTED_NOT_HERE = "Thanks, reported. The organizer will check it."
    const val REPORTED_MOVED = "Thanks, reported. The organizer will move it on the map."
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
    /** A guest's answers: saved, not on the map yet (deferred sign-up copy, never "on the map"). */
    fun savedForLater(todayCount: Int, noun: Noun): String = "Saved. That's ${noun.count(todayCount)} today."
    const val SAVED_FOR_LATER_NOTE = "Saved on our server. They go on the map when you finish on a computer."
    fun pendingLimit(noun: Noun): String =
        "That's as many ${noun.many} as we can keep before they go on the map. Finish on a computer, or sign in with I already use OpenStreetMap to keep going."
    /** Signed out in a build where guests can answer. Without a campaign, its places are "places". */
    fun noAccountNeeded(noun: Noun = Noun.PLACE): String =
        "No account needed to start. Answer a few ${noun.many} first, and we'll help you put them on the map afterwards."

    fun saved(todayCount: Int, noun: Noun): String = "Saved, thanks! That's ${noun.count(todayCount)} today."
    fun checkedByOtherFirst(noun: Noun): String =
        "Someone else checked this ${noun.one} first, so your answers weren't saved."
    fun next(noun: Noun, distance: String?): String = "Next nearest ${noun.one}" + (distance?.let { " · $it" } ?: "")
    fun lastOne(noun: Noun): String = "That was the last ${noun.one} to check. Thank you!"
    fun backToAll(noun: Noun): String = "Back to all ${noun.many}"

    // Campaign preview (design/preview-mode.md §8), as in the iOS app.
    const val PREVIEW_BANNER_TITLE = "Preview."
    const val PREVIEW_BANNER_TEXT = "Answers aren't saved."
    const val EXIT_PREVIEW = "Exit"
    const val PREVIEW_SIGN_IN_TITLE = "Sign in to preview this campaign"
    const val PREVIEW_SIGN_IN_TEXT =
        "This campaign isn't published yet. Sign in with the OpenStreetMap account that manages it."
    const val PREVIEW_SIGN_IN = "Sign in to OpenStreetMap"
    const val CANCEL = "Cancel"
    const val PREVIEW_NO_ACCESS_TITLE = "You can't preview this campaign"
    /** [account]: who is signed in now; the parenthesis is left out when unknown. */
    fun previewNoAccessText(account: String?): String =
        "Only the campaign's organizers can see it before it's published. Check you're signed in as the right account" +
            (account?.let { " (now: $it)." } ?: ".")
    const val SWITCH_ACCOUNT = "Switch account"
    const val CLOSE = "Close"
    const val PREVIEW_ALREADY_PUBLISHED = "This campaign is published, so answers are saved for real."
    fun previewingAs(account: String): String = "You're previewing as $account."
    /** The signed-in account as preview names it: the OpenStreetMap username (no "@"), else
     * "MapRoulette user 7" when the server didn't send one. */
    fun account(userId: Long?, displayName: String?): String? =
        displayName?.trim()?.takeIf { it.isNotEmpty() } ?: userId?.let { "MapRoulette user $it" }
    const val DONE_PREVIEW = "Done (preview)"
    const val PREVIEW_NOT_SAVED = "Preview: not saved."
    /** [realLine]: what the saved screen says in the real campaign, quoted. */
    fun previewWouldSay(realLine: String): String = "In the real campaign this would say: “$realLine”"
    const val PREVIEW_NOW_PUBLISHED = "This campaign is now published."
    const val OPEN_FOR_REAL = "Open it for real"
    /** Android has no pull to refresh here; the campaign screen has a Refresh button in preview. */
    fun previewNoPlaces(noun: Noun): String = "No ${noun.many} yet. Add them in the admin, then tap Refresh."
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
