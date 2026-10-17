package lol.osm.streettally.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.ChoiceEligibility
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.ChoiceProblem
import org.maproulette.sdk.ChoiceResult
import org.maproulette.sdk.ChoiceSubmission
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.IneligibleReason
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.MovedLocation
import org.maproulette.sdk.PendingChoice
import org.maproulette.sdk.PendingState
import org.maproulette.sdk.ProjectId
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskResolution
import org.maproulette.sdk.TaskStatus
import org.maproulette.sdk.WriteProblem
import org.maproulette.sdk.HttpMethod
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.MapRouletteEnvironment
import org.maproulette.sdk.Transport

private const val ME = 7L
private const val OTHER = 8L
private val ID = TaskId(42)

/** The pilot bench payload (docs/design/mobile-choice-challenges.md §3), shortened to two questions. */
internal val BENCH: JsonObject = Json.parseToJsonElement("""
    {"meta":{"version":2,"type":3,"choiceVersion":1},"element":"node/123","match":{"amenity":"bench"},
     "questions":[
      {"id":"backrest","prompt":"Does the bench have a backrest?","expect":{"backrest":null},
       "options":[{"id":"yes","label":"Yes","setTags":{"backrest":"yes"}},{"id":"no","label":"No","setTags":{"backrest":"no"}}]},
      {"id":"material","prompt":"What is the seat mainly made of?","expect":{"material":null},
       "options":[{"id":"wood","label":"Wood","setTags":{"material":"wood"}},{"id":"metal","label":"Metal","setTags":{"material":"metal"}}]}],
     "outcomes":[{"id":"not-a-bench","label":"Not a bench","status":2},{"id":"gone","label":"Bench is gone","delete":true}]}
""").jsonObject

/** [BENCH] in the mode the server keeps guest answers for. */
internal val GUEST_BENCH = JsonObject(BENCH + ("liveMissingQuestions" to JsonPrimitive(true)))

/** The outcomes a client with this deletion setting offers. */
internal fun outcomes(task: Task, deletion: Boolean = false): List<ChoiceOutcome> =
    MapRouletteClient(transport = Transport { error("no network in unit tests") }, allowElementDeletion = deletion)
        .choiceOutcomes(task)

internal fun task(status: Int = 0, lockedBy: Long? = null, completedBy: Long? = null, cooperativeWork: JsonObject? = BENCH,
                  bundleId: Long? = null, instruction: String? = null, changesetId: Long? = null) = Task(
    id = ID, challengeId = ChallengeId(1), name = "Bench", instruction = instruction, status = TaskStatus(status),
    geometry = Json.parseToJsonElement("""{"type":"FeatureCollection","features":[]}""").jsonObject,
    location = null, cooperativeWork = cooperativeWork, lockedBy = lockedBy, completedBy = completedBy, bundleId = bundleId,
    changesetId = changesetId,
)

internal val CHALLENGE = Challenge(ChallengeId(1), ProjectId(2), "Benches", "Add backrest", null, null, true, false, false)

private fun failure(kind: ErrorKind, problem: WriteProblem? = null) = MapRouletteException(kind, problem = problem)

/** Records every call; each operation's behavior is replaceable per test. No network. */
private class FakeOps(val allowElementDeletion: Boolean = false) : TaskOps {
    override fun choiceOutcomes(task: Task) = outcomes(task, allowElementDeletion)
    val calls = mutableListOf<String>()
    val submissions = mutableListOf<ChoiceSubmission>()
    var reads = ArrayDeque<() -> Task>()
    var check: suspend () -> ChoiceEligibility = { ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null) }
    var submit: suspend (ChoiceSubmission) -> ChoiceResult = { ChoiceResult(TaskStatus(1), 99) }
    var skip: suspend () -> Unit = {}
    var pending: suspend (ChoiceSubmission) -> PendingChoice = { PendingChoice(ID, state = PendingState.PENDING, answeredAt = Instant.EPOCH) }

    fun thenRead(vararg next: () -> Task) = apply { reads.addAll(next) }

    override suspend fun getTask(id: TaskId): Task {
        calls += "get"
        return (reads.removeFirstOrNull() ?: error("unexpected read"))()
    }

    override suspend fun getChallenge(task: Task) = CHALLENGE.also { calls += "challenge" }
    override suspend fun checkChoice(id: TaskId): ChoiceEligibility {
        calls += "check"
        return check()
    }

    override suspend fun submitChoice(task: Task, submission: ChoiceSubmission): ChoiceResult {
        calls += "submit"
        submissions += submission
        return submit(submission)
    }

    override suspend fun skipTask(id: TaskId) {
        calls += "skip"
        skip()
    }

    override suspend fun submitPendingChoice(task: Task, submission: ChoiceSubmission): PendingChoice {
        calls += "pending"
        submissions += submission
        return pending(submission)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskWorkControllerTest {
    private fun TestScope.controller(
        ops: FakeOps, writer: Writer? = Writer(ME, canEditOsm = true), outbox: GuestOutbox? = null,
        kept: (TaskId) -> Pair<Task, Challenge>? = { null },
    ): TaskWorkController =
        TaskWorkController(ops, writer, this, StandardTestDispatcher(testScheduler), outbox = outbox, kept = kept).also {
            it.load(ID)
            advanceUntilIdle()
        }

    private val TaskWorkController.answering get() = state.value as TaskScreen.Answering

    private fun TaskWorkController.outcome(id: String) = answering.form.outcomes.single { it.id == id }

    private fun TestScope.answered(ops: FakeOps): TaskWorkController = controller(ops).also {
        it.select("backrest", "yes")
        it.perform(ChoiceAction.Answers(mapOf("backrest" to "yes")))
        advanceUntilIdle()
    }

    @Test
    fun nonChoiceTasksAreNotAvailableAndNeverChecked() = runTest {
        val tagFix = Json.parseToJsonElement("""{"meta":{"version":2,"type":1},"operations":[]}""").jsonObject
        listOf(task(cooperativeWork = null), task(cooperativeWork = tagFix), task(bundleId = 3), task(status = 1)).forEach { t ->
            val ops = FakeOps().thenRead({ t })
            val state = controller(ops).state.value as TaskScreen.NotAvailable
            assertTrue(state.reason, state.reason.isNotBlank())
            assertEquals(listOf("get", "challenge"), ops.calls)
        }
    }

    @Test
    fun withoutWriterPreviewHasNoCheckAndNoWrites() = runTest {
        val ops = FakeOps().thenRead({ task() })
        val c = controller(ops, writer = null)
        assertTrue(c.state.value is TaskScreen.Preview)
        c.perform(ChoiceAction.Skip)
        advanceUntilIdle()
        assertEquals(listOf("get", "challenge"), ops.calls)
    }

    @Test
    fun ineligibleAtOpenNoLongerNeedsAnswering() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.check = { ChoiceEligibility(false, false, IneligibleReason.ELEMENT_GONE) }
        val c = controller(ops)
        assertEquals(IneligibleReason.ELEMENT_GONE, (c.state.value as TaskScreen.NoLongerNeeded).reason)
        assertTrue("the server now hides it: refresh the map", c.changed)
        assertEquals(listOf("get", "challenge", "check"), ops.calls)
    }

    @Test
    fun failedCheckIsNotActionableUntilARetrySucceeds() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task() })
        var checks = 0
        ops.check = {
            if (checks++ == 0) throw failure(ErrorKind.SERVER, ChoiceProblem.OsmUnavailable)
            ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null)
        }
        val c = controller(ops)
        assertTrue(c.state.value is TaskScreen.CheckFailed)
        assertFalse("not stale: the map keeps it", c.changed)
        c.perform(ChoiceAction.Outcome(outcomes(task()).first()))
        c.perform(ChoiceAction.Skip)
        advanceUntilIdle()
        assertTrue(ops.submissions.isEmpty())
        c.load(ID) // Retry
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Answering)
    }

    @Test
    fun writesSwitchedOffAtOpenSaySavingIsPausedWithoutARetry() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.check = { throw failure(ErrorKind.PERMISSION, WriteProblem.WritesPaused) }
        val c = controller(ops)
        assertEquals(TaskScreen.CheckFailed(task(), CHALLENGE, paused = true), c.state.value)
        assertFalse(c.changed)
    }

    @Test
    fun writesSwitchedOffAtSaveKeepTheAnswersBehindAnAlert() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.submit = { throw failure(ErrorKind.PERMISSION, WriteProblem.WritesPaused) }
        val c = answered(ops)
        assertTrue(c.answering.paused)
        assertEquals(null, c.answering.notice)
        assertEquals("yes", c.answering.form.answers["backrest"])
        c.dismissPaused()
        assertFalse(c.answering.paused)
        assertEquals("yes", c.answering.form.answers["backrest"])
    }

    @Test
    fun anotherPermissionRefusalAtSaveKeepsItsNotice() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.submit = { throw MapRouletteException(ErrorKind.PERMISSION, 403) }
        val c = answered(ops)
        assertFalse(c.answering.paused)
        assertEquals("This couldn't be saved. Nothing was changed. Try again later.", c.answering.notice)
    }

    @Test
    fun deletionOffShowsGoneAsNotAnIssue() = runTest {
        val c = controller(FakeOps(allowElementDeletion = false).thenRead({ task() }))
        assertEquals(listOf("not-a-bench", "gone", "too-hard"), c.answering.form.outcomes.map { it.id })
        val gone = c.outcome("gone")
        assertFalse(gone.deletesElement)
        assertEquals(TaskResolution.NOT_AN_ISSUE, gone.resolution)
    }

    @Test
    fun deletionOnShowsDeletingGoneOnlyWhenTheCheckAllowsIt() = runTest {
        val allowed = controller(FakeOps(allowElementDeletion = true).thenRead({ task() }))
        assertTrue(allowed.outcome("gone").deletesElement)
        assertEquals(TaskResolution.FIXED, allowed.outcome("gone").resolution)

        assertEquals(emptySet<String>(), allowed.answering.form.notDeletable)
    }

    @Test
    fun undeletableGoneIsOfferedAsNotAnIssueWithoutDeletion() = runTest {
        val inWay = FakeOps(allowElementDeletion = true).thenRead({ task() }, { task(status = 2, completedBy = ME) })
        inWay.check = { ChoiceEligibility(eligible = true, deleteAllowed = false, reason = null) }
        inWay.submit = { ChoiceResult(TaskStatus(2), null) }
        val c = controller(inWay)
        assertEquals(listOf("not-a-bench", "gone", "too-hard"), c.answering.form.outcomes.map { it.id })
        val gone = c.outcome("gone")
        assertFalse(gone.deletesElement)
        assertEquals(TaskResolution.NOT_AN_ISSUE, gone.resolution)
        assertEquals(setOf("gone"), c.answering.form.notDeletable)
        c.perform(ChoiceAction.Outcome(gone))
        advanceUntilIdle()
        assertFalse((inWay.submissions.single() as ChoiceSubmission.Outcome).outcome.deletesElement)
        assertTrue(c.state.value is TaskScreen.Done)

        val off = FakeOps(allowElementDeletion = false).thenRead({ task() })
        off.check = { ChoiceEligibility(eligible = true, deleteAllowed = false, reason = null) }
        assertEquals("deletion off: plain Not an issue, nothing blocked", emptySet<String>(), controller(off).answering.form.notDeletable)
    }

    @Test
    fun answersAndCantTell() = runTest {
        val c = controller(FakeOps().thenRead({ task() }))
        assertFalse("nothing answered", c.offers(c.answering.task, c.answering.form, ChoiceAction.Answers(emptyMap())))
        c.select("backrest", "yes")
        c.select("material", "wood")
        c.select("material", null) // Can't tell
        c.select("material", "granite") // Not an option: ignored
        c.select("color", "red") // Not a question: ignored
        assertEquals(mapOf("backrest" to "yes"), c.answering.form.answers)
        assertTrue(c.offers(c.answering.task, c.answering.form, ChoiceAction.Answers(c.answering.form.answers)))
        assertFalse(c.offers(c.answering.task, c.answering.form, ChoiceAction.Answers(mapOf("backrest" to "maybe"))))
    }

    @Test
    fun withoutOsmScopeEditsAreNotSentButPlainOutcomesAre() = runTest {
        val ops = FakeOps(allowElementDeletion = true).thenRead({ task() }, { task(status = 2, completedBy = ME) })
        val c = controller(ops, Writer(ME, canEditOsm = false))
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "yes")))
        c.perform(ChoiceAction.Outcome(c.outcome("gone")))
        advanceUntilIdle()
        assertTrue(ops.submissions.isEmpty())
        c.perform(ChoiceAction.Outcome(c.outcome("not-a-bench")))
        advanceUntilIdle()
        assertEquals(1, ops.submissions.size)
        assertTrue(c.state.value is TaskScreen.Done)
    }

    @Test
    fun submittedAnswersRereadAndReportTheChangeset() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 1, completedBy = ME) })
        val c = controller(ops)
        c.select("backrest", "yes")
        c.perform(ChoiceAction.Answers(c.answering.form.answers))
        assertTrue(c.state.value is TaskScreen.Submitting)
        assertTrue(c.busy)
        advanceUntilIdle()
        val done = c.state.value as TaskScreen.Done
        assertEquals(1, done.task.status?.code)
        assertEquals(ME, done.task.completedBy)
        assertEquals(99L, done.changesetId)
        assertEquals(listOf(ChoiceSubmission.Answers(mapOf("backrest" to "yes"))), ops.submissions)
        assertEquals(listOf("get", "challenge", "check", "submit", "get"), ops.calls)
        assertTrue(c.changed)
    }

    @Test
    fun failedRereadAfterSuccessCanBeRecheckedWithoutResending() = runTest {
        val ops = FakeOps().thenRead({ task() }, { throw failure(ErrorKind.NETWORK) }, { task(status = 1, completedBy = ME) })
        val c = answered(ops)
        assertTrue((c.state.value as TaskScreen.Done).readFailed)
        c.recheck()
        assertTrue("shows progress", (c.state.value as TaskScreen.Done).checking)
        c.recheck() // Ignored while checking.
        advanceUntilIdle()
        val done = c.state.value as TaskScreen.Done
        assertFalse(done.readFailed)
        assertEquals(99L, done.changesetId)
        assertEquals(1, ops.calls.count { it == "submit" })
    }

    @Test
    fun ineligibleAtSubmitNoLongerNeedsAnswering() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.submit = { throw failure(ErrorKind.CONFLICT, ChoiceProblem.TaskIneligible(IneligibleReason.MATCH_FAILED)) }
        assertEquals(IneligibleReason.MATCH_FAILED, (answered(ops).state.value as TaskScreen.NoLongerNeeded).reason)
    }

    @Test
    fun uncertainResultsWaitForManualCheckAndAreNeverResent() = runTest {
        listOf(
            failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown),
            failure(ErrorKind.CONFLICT, ChoiceProblem.SubmissionPending),
            failure(ErrorKind.SERVER, ChoiceProblem.StatusPending(55)),
        ).forEach { error ->
            val ops = FakeOps().thenRead({ task() }, { task(status = 1, completedBy = ME, changesetId = 55) })
            ops.submit = { throw error }
            val c = answered(ops)
            val pending = c.state.value as TaskScreen.CheckAgain
            assertFalse(pending.checking)
            assertFalse(c.busy)
            assertEquals(listOf("get", "challenge", "check", "submit"), ops.calls)
            c.perform(ChoiceAction.Answers(mapOf("backrest" to "yes"))) // Not allowed from this state.
            c.recheck()
            advanceUntilIdle()
            assertEquals(55L, (c.state.value as TaskScreen.Done).changesetId)
            assertEquals(1, ops.calls.count { it == "submit" })
        }
    }

    @Test
    fun uncertainEditIsOnlyResentManuallyAndIdentically() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(lockedBy = ME) }, { task() }, { task(status = 1, completedBy = ME) })
        var attempts = 0
        ops.submit = { if (attempts++ == 0) throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) else ChoiceResult(TaskStatus(1), 77) }
        val c = answered(ops)
        assertFalse("no resend before a check", (c.state.value as TaskScreen.CheckAgain).canResend)
        c.recheck()
        advanceUntilIdle()
        val held = c.state.value as TaskScreen.CheckAgain
        assertTrue(held.message, held.message.contains("Not saved yet") && held.canResend)
        c.recheck()
        advanceUntilIdle()
        val unlocked = c.state.value as TaskScreen.CheckAgain
        assertTrue("an edit never falls back to editable answers", unlocked.canResend)
        assertEquals(1, ops.calls.count { it == "submit" })
        c.resendSame()
        advanceUntilIdle()
        assertEquals(77L, (c.state.value as TaskScreen.Done).changesetId)
        assertEquals(List(2) { ChoiceSubmission.Answers(mapOf("backrest" to "yes")) }, ops.submissions)
    }

    @Test
    fun uncertainPlainOutcomeNotAppliedReturnsToAnswering() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task() })
        ops.submit = { throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) }
        val c = controller(ops)
        c.perform(ChoiceAction.Outcome(c.outcome("not-a-bench")))
        advanceUntilIdle()
        c.recheck()
        advanceUntilIdle()
        assertTrue(c.answering.notice!!.contains("weren't saved"))
    }

    @Test
    fun submissionPendingNeverInvitesAnotherSubmission() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task() })
        ops.submit = { throw failure(ErrorKind.CONFLICT, ChoiceProblem.SubmissionPending) }
        val c = answered(ops)
        c.recheck()
        advanceUntilIdle()
        val state = c.state.value as TaskScreen.CheckAgain
        assertFalse(state.canResend)
        assertTrue(state.message, state.message.contains("Pick another place"))
        c.resendSame()
        advanceUntilIdle()
        assertEquals(1, ops.calls.count { it == "submit" })
    }

    @Test
    fun statusPendingKeepsTheChangesetAndOffersTheSameSubmission() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task() }, { task(status = 1, completedBy = ME) })
        var attempts = 0
        ops.submit = { if (attempts++ == 0) throw failure(ErrorKind.SERVER, ChoiceProblem.StatusPending(55)) else ChoiceResult(TaskStatus(1), 55) }
        val c = answered(ops)
        assertTrue((c.state.value as TaskScreen.CheckAgain).canResend)
        c.recheck()
        advanceUntilIdle()
        val pending = c.state.value as TaskScreen.CheckAgain
        assertTrue(pending.message, pending.message.contains("changeset 55") && !pending.message.contains("Not saved"))
        assertEquals(55L, pending.changesetId)
        c.resendSame()
        advanceUntilIdle()
        assertEquals(55L, (c.state.value as TaskScreen.Done).changesetId)
    }

    @Test
    fun differentStatusWithoutAnotherCompleterIsNotByOther() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 2, completedBy = ME) })
        ops.submit = { throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) }
        val c = answered(ops)
        c.recheck()
        advanceUntilIdle()
        assertFalse((c.state.value as TaskScreen.Done).byOther)
    }

    @Test
    fun oneDeletionClientSendsGoneWithAndWithoutDeletion() = runTest {
        val bodies = mutableListOf<String?>()
        val start = """{"id":42,"parent":1,"name":"node/123","instruction":"","status":0,
            "geometries":{"type":"FeatureCollection","features":[]},"lockPrimaryTaskId":42,"lockBundledTasks":[]}"""
        val transport = Transport { request ->
            if (request.method == HttpMethod.POST) {
                bodies += request.body
                HttpResponse(200, body = """{"status":2,"changesetId":null}""")
            } else HttpResponse(200, body = start)
        }
        val ops = ClientTaskOps(MapRouletteClient(MapRouletteEnvironment.STAGING, transport = transport,
            accessToken = { "token" }, allowElementDeletion = true))
        val t = task()
        val gone = ops.choiceOutcomes(t).single { it.id == "gone" }
        ops.submitChoice(t, ChoiceSubmission.Outcome(ops.choiceOutcomes(t).single { it.id == "not-a-bench" }))
        ops.submitChoice(t, ChoiceSubmission.Outcome(TaskWorkController.withoutDeletion(gone)!!))
        ops.submitChoice(t, ChoiceSubmission.Outcome(gone))
        ops.submitChoice(t, ChoiceSubmission.Answers(mapOf("backrest" to "no")))
        assertEquals(listOf("""{"outcome":"not-a-bench"}""", """{"outcome":"gone"}""", """{"outcome":"gone","delete":true}""",
            """{"answers":{"backrest":"no"}}"""), bodies)
    }

    @Test
    fun failedCheckAgainReadStaysPending() = runTest {
        val ops = FakeOps().thenRead({ task() }, { throw failure(ErrorKind.NETWORK) })
        ops.submit = { throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) }
        val c = answered(ops)
        c.recheck()
        advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.CheckAgain).message.contains("Couldn't check whether"))
    }

    @Test
    fun osmPermissionProblemsAskToSignInAgain() = runTest {
        listOf(ChoiceProblem.OsmReauthRequired to ErrorKind.AUTHENTICATION, ChoiceProblem.OsmScopeRequired to ErrorKind.PERMISSION,
            WriteProblem.InsufficientScope to ErrorKind.PERMISSION).forEach { (problem, kind) ->
            val ops = FakeOps().thenRead({ task() })
            ops.submit = { throw failure(kind, problem) }
            val state = answered(ops).state.value as TaskScreen.SignInRequired
            assertTrue(state.reason, state.reason.contains("Sign in again"))
        }
    }

    @Test
    fun osmUnavailableExplainsAndKeepsTheAnswers() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.submit = { throw failure(ErrorKind.SERVER, ChoiceProblem.OsmUnavailable) }
        val c = answered(ops)
        assertEquals(mapOf("backrest" to "yes"), c.answering.form.answers)
        assertTrue(c.answering.notice!!.contains("Your answers are kept"))
    }

    @Test
    fun elementInUseOffersTheSameOutcomeWithoutDeletion() = runTest {
        val ops = FakeOps(allowElementDeletion = true).thenRead({ task() }, { task(status = 2, completedBy = ME) })
        ops.submit = { if ((it as ChoiceSubmission.Outcome).outcome.deletesElement) throw failure(ErrorKind.CONFLICT, ChoiceProblem.ElementInUse)
            else ChoiceResult(TaskStatus(2), null) }
        val c = controller(ops)
        c.perform(ChoiceAction.Outcome(c.outcome("gone")))
        advanceUntilIdle()
        val inUse = c.state.value as TaskScreen.ElementInUse
        assertEquals("gone", inUse.outcome.id)
        c.sendWithoutDeletion()
        advanceUntilIdle()
        val plain = (ops.submissions.last() as ChoiceSubmission.Outcome).outcome
        assertEquals("gone", plain.id)
        assertFalse(plain.deletesElement)
        assertEquals(TaskResolution.NOT_AN_ISSUE, plain.resolution)
        val done = c.state.value as TaskScreen.Done
        assertNull(done.changesetId)
        assertEquals(2, ops.submissions.size)
    }

    @Test
    fun lockedByOtherAtStartIsTakenByOther() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.submit = { throw failure(ErrorKind.PERMISSION, WriteProblem.LockedByOtherUser("locked")) }
        assertTrue(answered(ops).state.value is TaskScreen.TakenByOther)
    }

    @Test
    fun staleOwnLockOffersUserRetry() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 1, completedBy = ME) })
        var attempts = 0
        ops.submit = {
            if (attempts++ == 0) throw failure(ErrorKind.CONFLICT, WriteProblem.AlreadyHoldingTask(TaskId(9), null, null, null))
            ChoiceResult(TaskStatus(1), 99)
        }
        val c = answered(ops)
        val stale = c.state.value as TaskScreen.StaleOwnLock
        assertEquals(TaskId(9), stale.lockedTaskId)
        c.perform(stale.action)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Done)
    }

    @Test
    fun definiteFailuresReturnToAnsweringWithANotice() = runTest {
        listOf(failure(ErrorKind.CONFLICT, WriteProblem.LockLost), failure(ErrorKind.AUTHENTICATION),
            failure(ErrorKind.RATE_LIMIT), failure(ErrorKind.HTTP, ChoiceProblem.InvalidSubmission(null))).forEach { error ->
            val ops = FakeOps().thenRead({ task() })
            ops.submit = { throw error }
            val c = answered(ops)
            assertTrue(c.answering.notice!!.lowercase().let { it.contains("nothing was saved") || it.contains("wasn't saved") })
            assertEquals(mapOf("backrest" to "yes"), c.answering.form.answers)
        }
    }

    @Test
    fun invalidTransitionRereadsAndIsNotAvailable() = runTest {
        val ops = FakeOps().thenRead({ task() }, { task(status = 1, completedBy = OTHER) })
        ops.submit = { throw failure(ErrorKind.CONFLICT, WriteProblem.InvalidTransition) }
        val state = answered(ops).state.value as TaskScreen.NotAvailable
        assertEquals(1, state.task.status?.code)
        assertTrue(state.reason.contains("can't be answered right now"))
    }

    @Test
    fun unknownSkipIsNeverResent() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.skip = { throw failure(ErrorKind.NETWORK, WriteProblem.OutcomeUnknown) }
        val c = controller(ops)
        c.perform(ChoiceAction.Skip)
        advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.Skipped).uncertain)
        c.perform(ChoiceAction.Skip)
        advanceUntilIdle()
        assertEquals(1, ops.calls.count { it == "skip" })
        assertTrue(c.changed)
    }

    @Test
    fun cancelledScopeLetsTheWriteFinishButDiscardsItsResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val screen = CoroutineScope(SupervisorJob() + dispatcher)
        val ops = FakeOps().thenRead({ task() })
        val gate = CompletableDeferred<Unit>()
        var finished = false
        ops.submit = {
            gate.await()
            finished = true
            ChoiceResult(TaskStatus(1), 99)
        }
        val c = TaskWorkController(ops, Writer(ME, canEditOsm = true), screen, dispatcher)
        c.load(ID)
        advanceUntilIdle()
        c.select("backrest", "no")
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
        advanceUntilIdle()
        var idle = false
        screen.cancel() // Activity destroyed or account switched while the submission is in flight.
        c.whenIdle { idle = true }
        assertFalse(idle)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(finished)
        assertTrue(idle)
        assertTrue("late result is discarded", c.state.value is TaskScreen.Submitting)
        assertEquals(listOf("get", "challenge", "check", "submit"), ops.calls)
    }

    @Test
    fun notHereAndMovedAreReportsThatNeverNeedOsmEdits() = runTest {
        val spot = MovedLocation(40.7601, -111.8912, MovedLocation.Source.TAP)
        for (action in listOf(ChoiceAction.Outcome(ChoiceOutcome.NOT_HERE), ChoiceAction.Moved(spot))) {
            val ops = FakeOps().thenRead({ task() }, { task(status = 6, completedBy = ME) })
            ops.submit = { ChoiceResult(TaskStatus(6), null) }
            // A sign-in without osm:tagfix can still report.
            val c = controller(ops, writer = Writer(ME, canEditOsm = false))
            assertTrue(action.isReport)
            assertFalse(action.editsOsm)
            assertTrue(c.offers(c.answering.task, c.answering.form, action))
            c.perform(action)
            advanceUntilIdle()
            val done = c.state.value as TaskScreen.Done
            assertEquals(action, done.action)
            val expected = when (action) {
                is ChoiceAction.Moved -> ChoiceSubmission.Moved(spot)
                else -> ChoiceSubmission.Outcome(ChoiceOutcome.NOT_HERE)
            }
            assertEquals(listOf(expected), ops.submissions)
        }
        assertFalse(ChoiceAction.Outcome(ChoiceOutcome(ChoiceOutcome.TOO_HARD_ID, "Too hard", null, TaskResolution.TOO_HARD, false)).isReport)
    }

    @Test
    fun movedTooFarKeepsTheFormWithTheReason() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.submit = { throw failure(ErrorKind.HTTP, ChoiceProblem.MovedTooFar) }
        val c = controller(ops)
        c.perform(ChoiceAction.Moved(MovedLocation(40.8, -111.8, MovedLocation.Source.TAP)))
        advanceUntilIdle()
        assertEquals("That's too far to be the same place.", c.answering.notice)
    }

    @Test
    fun previewAsksEveryQuestionAndNeverWrites() = runTest {
        val ops = FakeOps().thenRead({ task() })
        ops.check = { error("preview never checks eligibility") }
        val c = TaskWorkController(ops, writer = null, scope = this, io = StandardTestDispatcher(testScheduler), previewing = true)
        c.load(ID)
        advanceUntilIdle()
        assertTrue(c.canEditOsm)
        val form = c.answering.form
        assertEquals(listOf("backrest", "material"), form.work.questions.map { it.id })
        val answers = ChoiceAction.Answers(mapOf("backrest" to "yes"))
        assertTrue(c.offers(c.answering.task, form, answers))
        c.perform(answers)
        advanceUntilIdle()
        val saved = c.state.value as TaskScreen.PreviewSaved
        assertEquals(answers, saved.action)
        assertFalse("nothing changed for maps and lists", c.changed)
        assertEquals(listOf("get", "challenge"), ops.calls)
        assertTrue(ops.submissions.isEmpty())
    }

    @Test
    fun previewOpsRefuseWritesBeforeTheSdk() = runTest {
        val ops = FakeOps()
        val preview = PreviewTaskOps(ops)
        val refused = runCatching { preview.submitChoice(task(), ChoiceSubmission.Answers(mapOf("backrest" to "yes"))) }
        assertEquals(ErrorKind.PERMISSION, (refused.exceptionOrNull() as MapRouletteException).kind)
        assertTrue(runCatching { preview.skipTask(ID) }.isFailure)
        assertTrue(runCatching { preview.submitPendingChoice(task(), ChoiceSubmission.Answers(mapOf("backrest" to "yes"))) }.isFailure)
        assertEquals(emptyList<String>(), ops.calls)
    }

    @Test
    fun guestSavesForLaterWithoutLockingOrEditingOsm() = runTest {
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        val c = controller(ops, writer = Writer.GUEST)
        val answers = ChoiceAction.Answers(mapOf("backrest" to "yes"))
        assertTrue(c.offers(c.answering.task, c.answering.form, answers))
        c.perform(answers)
        advanceUntilIdle()
        val done = c.state.value as TaskScreen.Done
        assertTrue(done.savedForLater)
        assertEquals(0L, done.me)
        assertTrue(c.changed)
        assertEquals(listOf("get", "challenge", "check", "pending"), ops.calls)
        assertEquals(listOf<ChoiceSubmission>(ChoiceSubmission.Answers(mapOf("backrest" to "yes"))), ops.submissions)
    }

    @Test
    fun guestSkipOnlyMovesOn() = runTest {
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        val c = controller(ops, writer = Writer.GUEST)
        val loaded = c.answering.task
        c.perform(ChoiceAction.Skip)
        advanceUntilIdle()
        assertEquals(TaskScreen.Skipped(loaded, CHALLENGE, uncertain = false), c.state.value)
        assertFalse("skip" in ops.calls)
    }

    @Test
    fun guestReportsAreSavedForLater() = runTest {
        val spot = MovedLocation(40.7609, -111.891, MovedLocation.Source.HERE)
        for (action in listOf(ChoiceAction.Outcome(ChoiceOutcome.NOT_HERE), ChoiceAction.Moved(spot))) {
            val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
            val c = controller(ops, writer = Writer.GUEST)
            assertTrue(c.offers(c.answering.task, c.answering.form, action))
            c.perform(action)
            advanceUntilIdle()
            val done = c.state.value as TaskScreen.Done
            assertTrue(done.savedForLater)
            assertEquals(listOf(action.submission), ops.submissions)
        }
    }

    @Test
    fun guestFailuresKeepTheAnswers() = runTest {
        val cases = listOf(
            MapRouletteException(ErrorKind.RATE_LIMIT, 429, reason = "pending_limit") to
                "That's as many places as one phone can answer without an account for now. Tap “I already use OpenStreetMap” to sign in and keep going.",
            MapRouletteException(ErrorKind.NETWORK) to "Couldn't connect. Check your connection and try again.",
            MapRouletteException(ErrorKind.AUTHENTICATION, 401) to "Couldn't save your answers. Try again in a moment.",
        )
        for ((error, notice) in cases) {
            val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
            ops.pending = { throw error }
            val c = controller(ops, writer = Writer.GUEST)
            c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
            advanceUntilIdle()
            assertEquals(notice, c.answering.notice)
        }
    }

    @Test
    fun guestOnAnUnpublishedCampaignIsNotAvailable() = runTest {
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        ops.pending = { throw MapRouletteException(ErrorKind.PERMISSION, 403, reason = "challenge_not_published") }
        val c = controller(ops, writer = Writer.GUEST)
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
        advanceUntilIdle()
        assertEquals("This campaign isn't open for answers yet.", (c.state.value as TaskScreen.NotAvailable).reason)
    }

    @Test
    fun guestAnswersATaskWithoutTheLiveFlag() = runTest {
        // Guests everywhere (G1): the server keeps guest answers on every campaign, so nothing is asked first.
        val c = controller(FakeOps().thenRead({ task() }), writer = Writer.GUEST)
        assertTrue(c.state.value is TaskScreen.Answering)
    }

    @Test
    fun guestRefusedAsUnsupportedIsAskedToSignIn() = runTest {
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        ops.pending = { throw MapRouletteException(ErrorKind.HTTP, 422, problem = ChoiceProblem.UnsupportedTask, reason = "unsupported_task") }
        val c = controller(ops, writer = Writer.GUEST)
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.GuestSignIn)
    }

    private class MemoryStore(var value: String? = null) : GuestOutbox.Store {
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }

    @Test
    fun guestAnswerWithNoConnectionWaitsOnThePhone() = runTest {
        // Before the server saw it, and after it stored it but the reply was lost (BACKLOG O1).
        for (error in listOf(MapRouletteException(ErrorKind.NETWORK), MapRouletteException(ErrorKind.PROTOCOL, 200, problem = WriteProblem.OutcomeUnknown))) {
            val outbox = GuestOutbox(MemoryStore())
            val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
            ops.pending = { throw error }
            val c = controller(ops, writer = Writer.GUEST, outbox = outbox)
            c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
            advanceUntilIdle()
            val done = c.state.value as TaskScreen.Done
            assertTrue(done.savedForLater && done.queued)
            assertEquals(listOf(GuestOutbox.Entry(ID.value, CHALLENGE.id.value, ChoiceSubmission.Answers(mapOf("backrest" to "no")))), outbox.entries.value)
        }
    }

    @Test
    fun inADeadSpotAGuestAnswersThePhonesCopyAndTheAnswerWaits() = runTest {
        val outbox = GuestOutbox(MemoryStore())
        val ops = FakeOps().thenRead({ throw MapRouletteException(ErrorKind.NETWORK) })
        ops.pending = { throw MapRouletteException(ErrorKind.NETWORK) }
        val keptTask = task(cooperativeWork = GUEST_BENCH)
        val c = controller(ops, writer = Writer.GUEST, outbox = outbox, kept = { if (it == ID) keptTask to CHALLENGE else null })
        // No eligibility check to reach: the server checks the place when the answer is sent.
        assertEquals(listOf("get"), ops.calls)
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
        advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.Done).queued)
        assertEquals(listOf(ID.value), outbox.entries.value.map { it.taskId })
    }

    @Test
    fun aHangingConnectionOpensThePhonesCopyAfterSixSeconds() = runTest {
        val keptTask = task(cooperativeWork = GUEST_BENCH)
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        ops.check = { delay(30_000); ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null) }
        val c = controller(ops, writer = Writer.GUEST, outbox = GuestOutbox(MemoryStore()), kept = { keptTask to CHALLENGE })
        assertTrue(c.state.value is TaskScreen.Answering)
        assertEquals(SLOW_MS, testScheduler.currentTime)
    }

    @Test
    fun aKnownHangOpensThePhonesCopyAtOnce() = runTest {
        val keptTask = task(cooperativeWork = GUEST_BENCH)
        val ops = FakeOps()
        val c = TaskWorkController(ops, Writer.GUEST, this, StandardTestDispatcher(testScheduler),
            outbox = GuestOutbox(MemoryStore()), kept = { keptTask to CHALLENGE })
        c.load(ID, offline = true)
        advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Answering)
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun timeSpentRegisteringComesOffTheWait() = runTest {
        val keptTask = task(cooperativeWork = GUEST_BENCH)
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        ops.check = { delay(30_000); ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null) }
        val c = TaskWorkController(ops, Writer.GUEST, this, StandardTestDispatcher(testScheduler),
            outbox = GuestOutbox(MemoryStore()), kept = { keptTask to CHALLENGE })
        c.load(ID, budgetMs = 2_000)
        advanceTimeBy(2_001)
        assertTrue(c.state.value is TaskScreen.Answering)
        assertEquals(2_001L, testScheduler.currentTime)
    }

    @Test
    fun anAnswerBeforeTheGuestRegisteredWaitsOnThePhone() = runTest {
        val outbox = GuestOutbox(MemoryStore())
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        val c = TaskWorkController(ops, Writer.GUEST, this, StandardTestDispatcher(testScheduler),
            outbox = outbox, unregistered = true)
        c.load(ID)
        advanceUntilIdle()
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
        advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.Done).queued)
        assertFalse("pending" in ops.calls)
        assertEquals(listOf(ID.value), outbox.entries.value.map { it.taskId })
    }

    private val keyChanged = MapRouletteException(ErrorKind.CONFLICT, 409,
        problem = ChoiceProblem.TaskIneligible(IneligibleReason.KEY_CHANGED), reason = "task_ineligible")

    /** A guest's live save refused with key_changed; after the first check, the place asks [now]. */
    private fun TestScope.liveKeyChanged(vararg now: String, eligible: Boolean = true, reason: IneligibleReason? = null): Pair<FakeOps, TaskWorkController> {
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        var checks = 0
        ops.check = {
            checks += 1
            if (checks == 1) ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null)
            else ChoiceEligibility(eligible = eligible, deleteAllowed = eligible, reason = reason, questionIds = setOf(*now).takeIf { eligible })
        }
        var sent = 0
        ops.pending = { sent += 1; if (sent == 1) throw keyChanged; PendingChoice(ID, state = PendingState.PENDING, answeredAt = Instant.EPOCH) }
        val c = controller(ops, writer = Writer.GUEST, outbox = GuestOutbox(MemoryStore()))
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
        advanceUntilIdle()
        return ops to c
    }

    @Test
    fun aLiveSaveTheChangedPlaceStillNeedsIsResentQuietly() = runTest {
        val (ops, c) = liveKeyChanged("backrest")
        val done = c.state.value as TaskScreen.Done
        assertFalse(done.queued)
        assertEquals(2, ops.submissions.size)
    }

    @Test
    fun aLiveSaveTheChangedPlaceAsksMoreOfIsDropped() = runTest {
        val (ops, c) = liveKeyChanged("backrest", "armrest")
        val changed = c.state.value as TaskScreen.NoLongerNeeded
        assertTrue(changed.dropped)
        assertEquals(1, ops.submissions.size)
    }

    @Test
    fun aLiveSaveOnAPlaceSomeoneElseFilledSaysAlreadyChecked() = runTest {
        val (_, c) = liveKeyChanged(eligible = false, reason = IneligibleReason.KEY_CHANGED)
        val checked = c.state.value as TaskScreen.NoLongerNeeded
        assertFalse(checked.dropped)
        assertEquals(IneligibleReason.KEY_CHANGED, checked.reason)
    }

    @Test
    fun aLiveSaveOnAGonePlaceSaysItIsNoLongerOnTheMap() = runTest {
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        ops.pending = { throw MapRouletteException(ErrorKind.NOT_FOUND, 404) }
        val c = controller(ops, writer = Writer.GUEST, outbox = GuestOutbox(MemoryStore()))
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
        advanceUntilIdle()
        assertEquals(IneligibleReason.ELEMENT_GONE, (c.state.value as TaskScreen.NoLongerNeeded).reason)
    }

    @Test
    fun withoutThePhonesCopyASlowReadIsWaitedFor() = runTest {
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        ops.check = { delay(20_000); ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null) }
        val c = controller(ops, writer = Writer.GUEST, outbox = GuestOutbox(MemoryStore()))
        assertTrue(c.state.value is TaskScreen.Answering)
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test
    fun aGuestSaveWithNoReplyAfterSixSecondsWaitsOnThePhone() = runTest {
        val outbox = GuestOutbox(MemoryStore())
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        val c = controller(ops, writer = Writer.GUEST, outbox = outbox)
        ops.pending = { delay(30_000); PendingChoice(ID, state = PendingState.PENDING, answeredAt = Instant.EPOCH) }
        val start = testScheduler.currentTime
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
        advanceTimeBy(SLOW_MS + 1)
        assertTrue((c.state.value as TaskScreen.Done).queued)
        assertEquals(listOf(ID.value), outbox.entries.value.map { it.taskId })
        // The late reply changes nothing on screen; the resend replaces it on the server.
        advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.Done).queued)
        assertTrue(testScheduler.currentTime - start >= 30_000)
    }

    @Test
    fun thePhonesCopyIsOnlyForAGuestWithNoConnection() = runTest {
        val keptTask = task(cooperativeWork = GUEST_BENCH)
        val kept: (TaskId) -> Pair<Task, Challenge>? = { keptTask to CHALLENGE }
        // Signed in: no outbox to wait in.
        val signedIn = FakeOps().thenRead({ throw MapRouletteException(ErrorKind.NETWORK) })
        assertTrue(controller(signedIn, outbox = GuestOutbox(MemoryStore()), kept = kept).state.value is TaskScreen.LoadFailed)
        // The server answered: its word stands.
        val gone = FakeOps().thenRead({ throw MapRouletteException(ErrorKind.NOT_FOUND, 404) })
        assertTrue(controller(gone, writer = Writer.GUEST, outbox = GuestOutbox(MemoryStore()), kept = kept).state.value is TaskScreen.LoadFailed)
    }

    @Test
    fun aGuestAnswerThatGetsThroughSendsTheWaitingOnes() = runTest {
        val outbox = GuestOutbox(MemoryStore())
        outbox.add(GuestOutbox.Entry(41, CHALLENGE.id.value, ChoiceSubmission.Answers(mapOf("backrest" to "yes"))))
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) }, { task(cooperativeWork = GUEST_BENCH).copy(id = TaskId(41)) })
        val c = controller(ops, writer = Writer.GUEST, outbox = outbox)
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
        advanceUntilIdle()
        assertFalse((c.state.value as TaskScreen.Done).queued)
        assertEquals(listOf("get", "challenge", "check", "pending", "get", "pending"), ops.calls)
        assertTrue(outbox.entries.value.isEmpty())
    }

    @Test
    fun otherGuestFailuresStayOnTheScreenWithAnOutbox() = runTest {
        val outbox = GuestOutbox(MemoryStore())
        val ops = FakeOps().thenRead({ task(cooperativeWork = GUEST_BENCH) })
        ops.pending = { throw MapRouletteException(ErrorKind.RATE_LIMIT, 429, reason = "pending_limit") }
        val c = controller(ops, writer = Writer.GUEST, outbox = outbox)
        c.perform(ChoiceAction.Answers(mapOf("backrest" to "no")))
        advanceUntilIdle()
        assertTrue(c.answering.notice!!.startsWith("That's as many places"))
        assertTrue(outbox.entries.value.isEmpty())
    }
}
