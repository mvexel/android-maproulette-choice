package lol.osm.streettally.campaign

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.maproulette.sdk.ClaimState
import org.maproulette.sdk.ClaimStatus
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.GuestClaim
import org.maproulette.sdk.GuestStatus
import org.maproulette.sdk.MapRouletteException
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

        /** Key and value of the identity tag; a null value matches any (Woodbine's entrance=main|yes). */
        private val known = listOf<Triple<String, String?, Noun>>(
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
            Triple("entrance", null, Noun("entrance", "entrances")),
        )

        fun from(match: Map<String, String>): Noun =
            known.firstOrNull { (key, value, _) -> match[key] != null && (value == null || match[key] == value) }?.third ?: PLACE

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
    /** Without a location, places aren't in distance order, so nothing is called "nearest" (Iris fix 4). */
    fun choose(noun: Noun): String = "Choose a ${noun.one}"
    fun noLocation(noun: Noun, permitted: Boolean): String =
        (if (permitted) "Your location isn't available yet." else "Location is off.") +
            " Choose a ${noun.one} on the map or in the list."
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
        "That's as many ${noun.many} as we can keep before they go on the map. Finish on a computer, or tap “I already use OpenStreetMap” to sign in and keep going."
    /** Signed out in a build where guests can answer. Without a campaign, its places are "places". */
    fun noAccountNeeded(noun: Noun = Noun.PLACE): String =
        "No account needed to start. Answer a few ${noun.many} first, and we'll help you put them on the map afterwards."

    // Finishing later (deferred sign-up §1.3–§1.6), with Iris's fixes (design/copy-fixes-2026-10-11.md).
    const val PUT_ON_MAP_TITLE = "Your answers are saved"
    const val PUT_ON_MAP_INTRO = "To put them on the map, finish on a computer. We can email you a link."
    const val EMAIL_USE = "We only email you about these answers, and delete your address once they're on the map or removed."
    /** Only after a successful send: a stored address doesn't prove an email went out. */
    fun linkSent(email: String, until: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        "Sent to $email. Check your email later." + (until?.let { " The link works until ${day(it, zone)}." } ?: "")
    /** "Nov 30": the one date format for guest deadlines. A non-breaking space keeps month and day
     * on one line. */
    fun day(date: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(date.atZone(zone)).replace(' ', '\u00A0')
    const val ENTER_EMAIL_AGAIN = "Enter your email again and we'll send a new link."
    const val SEND_ME_THE_LINK = "Send me the link"
    const val RESEND_THE_LINK = "Resend the link"
    const val I_ALREADY_USE_OSM = "I already use OpenStreetMap"
    /** Phone sign-in doesn't claim saved answers yet (Iris fix 5). */
    /** Phone sign-in claims the saved answers (B5), so the original line is back (copy fix 5). */
    const val SIGN_IN_PUTS_ON_MAP = "Sign in now and your saved answers go on the map right away."
    /** Only a bad address is the participant's to fix (shown in the error color); the rest are calm. */
    fun isInputError(error: Throwable): Boolean =
        error is IllegalArgumentException || (error as? MapRouletteException)?.status.let { it == 400 || it == 422 }
    /** The backend sends the link only once the guest has a saved answer (409 `nothing_saved`). */
    fun isNothingSaved(error: Throwable): Boolean =
        error is MapRouletteException && error.kind == ErrorKind.CONFLICT && error.reason == "nothing_saved"
    fun linkFailed(error: Throwable, noun: Noun = Noun.PLACE): String {
        if (isNothingSaved(error)) return "Answer a ${noun.one} first, then we can send you the link."
        if (isInputError(error)) return "That doesn't look like an email address."
        val e = error as? MapRouletteException
        return when {
            e?.kind == ErrorKind.RATE_LIMIT -> "We've sent a few links already. Try again tomorrow."
            e?.kind == ErrorKind.NETWORK -> "Couldn't connect. Check your connection and try again."
            e?.kind == ErrorKind.CONFLICT && e.reason == "guest_claimed" ->
                "These answers are already linked to an OpenStreetMap account. Sign in with it to see them."
            else -> "Couldn't send the link right now. Try again later."
        }
    }
    fun savedNotOnMap(count: Int, noun: Noun, until: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        "${noun.count(count)} saved, not on the map yet." + (until?.let { " Finish on a computer by ${day(it, zone)}." } ?: "")
    const val DAY_SUMMARY_TITLE = "Nice work today."
    /** "You checked 14 stops. The tally: 212 of 410." */
    fun daySummary(checked: Int, done: Int, total: Int, noun: Noun): String =
        "You checked ${noun.count(checked)}. The tally: $done of $total."
    fun daySummarySaved(until: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        (until?.let { "They're saved until ${day(it, zone)}." } ?: "They're saved for now.") +
            " If we emailed you a link, open it on a computer to put them on the map."
    /** A guest's saved place on the map and in the list (the pending pin). */
    const val SAVED_NOT_ON_MAP = "Saved, not on the map yet"
    const val DONE_FOR_TODAY = "I'm done for today"
    /** In the last 3 days before the saved answers are removed (§1.6). */
    fun savedClosing(count: Int, noun: Noun, on: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        "$count saved ${if (count == 1) noun.one else noun.many} will be removed on ${day(on, zone)} unless you finish on a computer."
    /** After the deadline: calm, no error tone (§1.6). */
    const val SAVED_EXPIRED =
        "Your saved answers weren't put on the map, so we removed them. They still count in the campaign's totals. Thanks for checking."
    const val DELETE_MY_DATA = "Delete my data"
    const val DELETE_MY_DATA_EXPLAINED =
        "Removes your email and any saved answers that aren't on the map yet. Answers already on the map stay there, because they belong to OpenStreetMap now."
    const val DELETE_MY_DATA_DONE = "Done. Your email and saved answers are deleted."
    const val DELETE_MY_DATA_FAILED = "Couldn't delete right now. Check your connection and try again."
    /** A guest whose answers were claimed on the web, now signed in (§1.5). */
    fun claimedMessage(status: GuestStatus?): String {
        val who = status?.claimedAs?.let { "You're signed in as ${it.displayName}." } ?: "You're signed in with OpenStreetMap."
        val count = status?.publishedCount ?: 0
        val onMap = when (count) {
            0 -> ""
            1 -> " Your answer is on the map."
            else -> " Your $count answers are on the map."
        }
        return "$who$onMap New answers go straight on the map from now on."
    }

    /** Phone claim lines (B5, Iris's list in copy-fixes-2026-10-11.md): the ones about the claim
     * itself start by naming the account, since answers can turn out to be linked to another one. */
    fun signedInAs(displayName: String?): String =
        displayName?.let { "You're signed in as $it." } ?: "You're signed in with OpenStreetMap."

    /** "I already use OpenStreetMap" with saved answers: the claim was accepted. */
    fun claimStarted(claim: GuestClaim): String {
        val n = claim.pendingCount
        val rest = when {
            n == 0 -> "New answers go straight on the map from now on."
            claim.state == ClaimState.WAITING ->
                if (n == 1) "Your saved answer is now linked to your account. It'll go on the map later."
                else "Your $n saved answers are now linked to your account. They'll go on the map later."
            n == 1 -> "Putting your saved answer on the map…"
            else -> "Putting your $n saved answers on the map…"
        }
        return "${signedInAs(claim.account.displayName)} $rest"
    }

    /** The publish job's progress, then its result. Counts come from the job, never from the claim. */
    fun claimProgress(status: ClaimStatus): String {
        val onMap = status.published
        val notAdded = status.skipped + status.superseded
        return when (status.state) {
            ClaimState.QUEUED, ClaimState.RUNNING -> "Putting your answers on the map… $onMap of ${status.total}"
            ClaimState.WAITING -> "Your saved answers are now linked to your account. They'll go on the map later."
            ClaimState.DONE, ClaimState.FAILED -> when {
                status.failed > 0 -> "$onMap of ${status.total} are on the map. The rest are still saved."
                else -> buildString {
                    append(if (onMap == 1) "✓ 1 answer is on the map." else "✓ $onMap answers are on the map.")
                    if (notAdded == 1) append(" 1 wasn't added, because someone changed that place on the map after you checked.")
                    if (notAdded > 1) append(" $notAdded weren't added, because someone changed those places on the map after you checked.")
                }
            }
        }
    }

    /** The guest is gone: expired, deleted, or claimed by another account. */
    fun claimGone(displayName: String?): String = "${signedInAs(displayName)} We couldn't find your saved answers. " +
        "They may have expired, or they're linked to a different OpenStreetMap account."
    /** OpenStreetMap needs a fresh sign-in before the answers can be published. No "You're signed in"
     * prefix: it would contradict the ask (Iris, 2026-10-11). */
    const val CLAIM_SIGN_IN_AGAIN = "Sign in again so we can put your saved answers on the map."
    /** The claim didn't go through (offline, or the server was busy); tried again on the next launch. */
    fun claimLater(displayName: String?): String =
        "${signedInAs(displayName)} Your answers are still saved. We'll try again next time you open the app."

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
    const val UPDATE_LOCATION = "Update my location"
    /** A draft opened without its preview link (an old link, the last campaign). */
    const val NOT_PUBLISHED = "This campaign isn't published yet."
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
