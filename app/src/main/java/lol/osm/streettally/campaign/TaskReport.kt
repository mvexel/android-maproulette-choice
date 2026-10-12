package lol.osm.streettally.campaign

import lol.osm.streettally.task.ChoiceAction
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.MapRouletteClient

/** A place someone reported (design/not-here-moved.md §4): not there, or moved to [Moved.spot]. */
sealed interface TaskReport {
    data object NotHere : TaskReport
    data class Moved(val spot: LatLon) : TaskReport

    companion object {
        /** The report [action] makes, or null when it isn't "It's not here" or "It's moved". */
        fun of(action: ChoiceAction): TaskReport? = when {
            action is ChoiceAction.Moved -> Moved(LatLon(action.location.latitude, action.location.longitude))
            action is ChoiceAction.Outcome && action.outcome == ChoiceOutcome.NOT_HERE -> NotHere
            else -> null
        }

        /** The banner for someone else's [report] on a place at [place]; null when there's nothing to say. */
        fun banner(report: TaskReport?, place: LatLon?, noun: Noun): String? = when (report) {
            TaskReport.NotHere -> Wording.someoneReportedNotHere(noun)
            is TaskReport.Moved -> place?.let { Wording.someoneReportedMoved(Places.meters(it, report.spot), noun) }
            null -> null
        }
    }
}

/**
 * Other people's reports in a campaign, by task id. The backend has no reports read yet (E1/E2), so
 * the app uses [NONE] and only tests feed one; the serverReports build flag keeps it off until then.
 */
fun interface ReportSource {
    suspend fun reports(client: MapRouletteClient, campaign: ChallengeId): Map<Long, TaskReport>

    companion object {
        val NONE = ReportSource { _, _ -> emptyMap() }
    }
}
