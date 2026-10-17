package lol.osm.streettally.task

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.ChoiceEligibility
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.ChoiceProblem
import org.maproulette.sdk.ChoiceSubmission
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.IneligibleReason
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.MovedLocation
import org.maproulette.sdk.PendingChoice
import org.maproulette.sdk.PendingState
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskResolution
import org.maproulette.sdk.WriteProblem
import java.time.Instant

class GuestOutboxTest {
    private class MemoryStore(var value: String? = null) : GuestOutbox.Store {
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }

    /** Sends each queued answer through [send]; reads always succeed, and [check] answers the choice check. */
    private class Ops(
        val check: () -> ChoiceEligibility = { ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null) },
        val send: (Long, ChoiceSubmission) -> Unit = { _, _ -> },
    ) : TaskOps {
        val sent = mutableListOf<Pair<Long, ChoiceSubmission>>()
        override fun choiceOutcomes(task: Task) = emptyList<ChoiceOutcome>()
        override suspend fun getTask(id: TaskId) = task().copy(id = id)
        override suspend fun getChallenge(task: Task) = CHALLENGE
        override suspend fun checkChoice(id: TaskId) = check()
        override suspend fun submitChoice(task: Task, submission: ChoiceSubmission) = error("not a guest")
        override suspend fun skipTask(id: TaskId) = error("not a guest")
        override suspend fun submitPendingChoice(task: Task, submission: ChoiceSubmission): PendingChoice {
            send(task.id.value, submission)
            sent += task.id.value to submission
            return PendingChoice(task.id, state = PendingState.PENDING, answeredAt = Instant.EPOCH)
        }
    }

    private val answers = ChoiceSubmission.Answers(mapOf("backrest" to "yes", "armrest" to "no"))
    private val gone = ChoiceSubmission.Outcome(ChoiceOutcome("gone", "It's gone", "No bench here now", TaskResolution.FIXED, deletesElement = true))
    private val moved = ChoiceSubmission.Moved(MovedLocation(40.7609, -111.891, MovedLocation.Source.HERE))

    @Test fun answersSurviveARestart() {
        val store = MemoryStore()
        val outbox = GuestOutbox(store)
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        outbox.add(GuestOutbox.Entry(2, 3, gone))
        outbox.add(GuestOutbox.Entry(3, 4, moved))
        outbox.add(GuestOutbox.Entry(4, 4, ChoiceSubmission.Outcome(ChoiceOutcome.NOT_HERE)))
        assertEquals(outbox.entries.value, GuestOutbox(store).entries.value)
        assertEquals(4, GuestOutbox(store).entries.value.size)
    }

    @Test fun aNewerAnswerReplacesTheWaitingOne() {
        val outbox = GuestOutbox(MemoryStore())
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        outbox.add(GuestOutbox.Entry(1, 3, gone))
        assertEquals(listOf(GuestOutbox.Entry(1, 3, gone)), outbox.entries.value)
    }

    @Test fun anUnreadableQueueIsEmptyAndABadEntryIsSkipped() {
        assertTrue(GuestOutbox(MemoryStore("not json")).entries.value.isEmpty())
        val one = GuestOutbox.encode(listOf(GuestOutbox.Entry(1, 3, answers)))
        val mixed = one.dropLast(1) + """,{"task":"x"}]"""
        assertEquals(listOf(GuestOutbox.Entry(1, 3, answers)), GuestOutbox(MemoryStore(mixed)).entries.value)
    }

    @Test fun sendingEmptiesTheQueueInOrder() = runTest {
        val store = MemoryStore()
        val outbox = GuestOutbox(store)
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        outbox.add(GuestOutbox.Entry(2, 3, gone))
        val ops = Ops()
        assertEquals(2, outbox.send(ops))
        assertEquals(listOf(1L to answers, 2L to gone), ops.sent)
        assertTrue(outbox.entries.value.isEmpty())
        assertEquals(null, store.value)
    }

    @Test fun stillOfflineKeepsTheAnswerAndTheRest() = runTest {
        val outbox = GuestOutbox(MemoryStore())
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        outbox.add(GuestOutbox.Entry(2, 3, gone))
        for (error in listOf(
            MapRouletteException(ErrorKind.NETWORK),
            MapRouletteException(ErrorKind.PROTOCOL, 200, problem = WriteProblem.OutcomeUnknown),
            MapRouletteException(ErrorKind.SERVER, 503),
        )) {
            assertEquals(0, outbox.send(Ops { _, _ -> throw error }))
            assertEquals(listOf(1L, 2L), outbox.entries.value.map { it.taskId })
        }
    }

    @Test fun anAnswerTheServerRefusesForGoodIsDropped() = runTest {
        val dropped = mutableListOf<Long>()
        val outbox = GuestOutbox(MemoryStore()) { dropped += it.taskId }
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        outbox.add(GuestOutbox.Entry(2, 3, gone))
        val ops = Ops { id, _ -> if (id == 1L) throw MapRouletteException(ErrorKind.CONFLICT, 409, reason = "task_completed") }
        assertEquals(1, outbox.send(ops))
        assertEquals(listOf(1L), dropped)
        assertEquals(listOf(2L to gone), ops.sent)
        assertTrue(outbox.entries.value.isEmpty())
        assertEquals(mapOf(3L to 1), outbox.notSent.value)
        outbox.notSentShown(3)
        assertEquals(0, outbox.notSent.value.values.sum())
    }

    private val keyChanged = MapRouletteException(ErrorKind.CONFLICT, 409,
        problem = ChoiceProblem.TaskIneligible(IneligibleReason.KEY_CHANGED), reason = "task_ineligible")
    private fun asks(vararg ids: String) = { ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null, questionIds = setOf(*ids)) }

    @Test fun answersStillCoveringAChangedPlaceAreResentTrimmed() = runTest {
        val dropped = mutableListOf<Long>()
        val outbox = GuestOutbox(MemoryStore()) { dropped += it.taskId }
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        // Backrest got mapped meanwhile: the place asks only armrest now.
        val ops = Ops(check = asks("armrest"), send = { _, s -> if (s == answers) throw keyChanged })
        assertEquals(1, outbox.send(ops))
        assertEquals(listOf(1L to ChoiceSubmission.Answers(mapOf("armrest" to "no"))), ops.sent)
        assertTrue(dropped.isEmpty())
        assertEquals(0, outbox.notSent.value.values.sum())
        assertTrue(outbox.entries.value.isEmpty())
    }

    @Test fun aChangedPlaceAskingSomethingUnansweredDropsTheAnswerAndSaysSo() = runTest {
        val dropped = mutableListOf<Long>()
        val outbox = GuestOutbox(MemoryStore()) { dropped += it.taskId }
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        outbox.add(GuestOutbox.Entry(2, 3, ChoiceSubmission.Answers(mapOf("armrest" to "no"))))
        for (check in listOf(asks("armrest", "seat"), { ChoiceEligibility(eligible = false, deleteAllowed = false, reason = IneligibleReason.KEY_CHANGED) })) {
            outbox.add(GuestOutbox.Entry(1, 3, answers))
            val ops = Ops(check = check, send = { id, _ -> if (id == 1L) throw keyChanged })
            outbox.send(ops)
        }
        assertEquals(listOf(1L, 1L), dropped)
        assertEquals(2, outbox.notSent.value.values.sum())
        assertTrue(outbox.entries.value.isEmpty())
    }

    @Test fun aChangedPlaceThatCantBeReadYetKeepsTheAnswer() = runTest {
        val outbox = GuestOutbox(MemoryStore())
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        val ops = Ops(check = { throw MapRouletteException(ErrorKind.NETWORK) }, send = { _, _ -> throw keyChanged })
        assertEquals(0, outbox.send(ops))
        assertEquals(listOf(1L), outbox.entries.value.map { it.taskId })
        assertEquals(0, outbox.notSent.value.values.sum())
    }

    @Test fun aGonePlaceSaysSoButTheLimitAndAClosedCampaignDont() = runTest {
        val outbox = GuestOutbox(MemoryStore())
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        outbox.send(Ops { _, _ -> throw MapRouletteException(ErrorKind.NOT_FOUND, 404) })
        assertEquals(1, outbox.notSent.value.values.sum())
        outbox.notSentShown(3)
        for (error in listOf(
            MapRouletteException(ErrorKind.RATE_LIMIT, 429, reason = "pending_limit"),
            MapRouletteException(ErrorKind.PERMISSION, 403, reason = "challenge_not_published"),
        )) {
            outbox.add(GuestOutbox.Entry(1, 3, answers))
            outbox.send(Ops { _, _ -> throw error })
            assertTrue(outbox.entries.value.isEmpty())
        }
        assertEquals(0, outbox.notSent.value.values.sum())
    }

    @Test fun thePhonesAnswerLimitDropsTheAnswerAndSaysSoOnce() = runTest {
        val outbox = GuestOutbox(MemoryStore())
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        assertEquals(0, outbox.send(Ops { _, _ -> throw MapRouletteException(ErrorKind.RATE_LIMIT, 429, reason = "pending_limit") }))
        assertTrue(outbox.entries.value.isEmpty())
        assertTrue(outbox.limitReached.value)
        outbox.limitShown()
        assertFalse(outbox.limitReached.value)
    }

    @Test fun deletingMyDataEmptiesTheQueueAndOpensThePlaces() {
        val store = MemoryStore()
        val dropped = mutableListOf<Long>()
        val outbox = GuestOutbox(store) { dropped += it.taskId }
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        outbox.clear()
        assertEquals(listOf(1L), dropped)
        assertTrue(outbox.entries.value.isEmpty())
        assertEquals(null, store.value)
    }

    @Test fun whatIsWorthRetrying() {
        assertTrue(GuestOutbox.retryLater(MapRouletteException(ErrorKind.AUTHENTICATION, 401)))
        assertTrue(GuestOutbox.retryLater(MapRouletteException(ErrorKind.RATE_LIMIT, 429)))
        assertFalse(GuestOutbox.retryLater(MapRouletteException(ErrorKind.RATE_LIMIT, 429, reason = "pending_limit")))
        assertFalse(GuestOutbox.retryLater(MapRouletteException(ErrorKind.PERMISSION, 403, reason = "challenge_not_published")))
        assertFalse(GuestOutbox.retryLater(IllegalArgumentException("bad answer")))
    }

    @Test fun retriesBackOffToFiveMinutes() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L),
            (0..5).map(GuestOutbox::retryDelayMs))
    }

    @Test fun eachCampaignKeepsItsOwnNotSentCount() = runTest {
        val outbox = GuestOutbox(MemoryStore())
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        outbox.add(GuestOutbox.Entry(2, 4, answers))
        outbox.send(Ops { _, _ -> throw MapRouletteException(ErrorKind.NOT_FOUND, 404) })
        assertEquals(mapOf(3L to 1, 4L to 1), outbox.notSent.value)
        // Seen on one campaign's screen: the other still has its line to show.
        outbox.notSentShown(3)
        assertEquals(mapOf(4L to 1), outbox.notSent.value)
    }
}
