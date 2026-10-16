package lol.osm.streettally.task

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.ChoiceEligibility
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.ChoiceSubmission
import org.maproulette.sdk.ErrorKind
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

    /** Sends each queued answer through [send]; reads always succeed. */
    private class Ops(val send: (Long) -> Unit = {}) : TaskOps {
        val sent = mutableListOf<Pair<Long, ChoiceSubmission>>()
        override fun choiceOutcomes(task: Task) = emptyList<ChoiceOutcome>()
        override suspend fun getTask(id: TaskId) = task().copy(id = id)
        override suspend fun getChallenge(task: Task) = CHALLENGE
        override suspend fun checkChoice(id: TaskId) = ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null)
        override suspend fun submitChoice(task: Task, submission: ChoiceSubmission) = error("not a guest")
        override suspend fun skipTask(id: TaskId) = error("not a guest")
        override suspend fun submitPendingChoice(task: Task, submission: ChoiceSubmission): PendingChoice {
            send(task.id.value)
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
            assertEquals(0, outbox.send(Ops { throw error }))
            assertEquals(listOf(1L, 2L), outbox.entries.value.map { it.taskId })
        }
    }

    @Test fun anAnswerTheServerRefusesForGoodIsDropped() = runTest {
        val dropped = mutableListOf<Long>()
        val outbox = GuestOutbox(MemoryStore()) { dropped += it.taskId }
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        outbox.add(GuestOutbox.Entry(2, 3, gone))
        val ops = Ops { if (it == 1L) throw MapRouletteException(ErrorKind.CONFLICT, 409, reason = "task_completed") }
        assertEquals(1, outbox.send(ops))
        assertEquals(listOf(1L), dropped)
        assertEquals(listOf(2L to gone), ops.sent)
        assertTrue(outbox.entries.value.isEmpty())
    }

    @Test fun thePhonesAnswerLimitDropsTheAnswerAndSaysSoOnce() = runTest {
        val outbox = GuestOutbox(MemoryStore())
        outbox.add(GuestOutbox.Entry(1, 3, answers))
        assertEquals(0, outbox.send(Ops { throw MapRouletteException(ErrorKind.RATE_LIMIT, 429, reason = "pending_limit") }))
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
}
