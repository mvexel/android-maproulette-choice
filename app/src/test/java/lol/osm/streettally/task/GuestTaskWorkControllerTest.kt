package lol.osm.streettally.task

import java.time.Instant
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.maproulette.sdk.*

private const val BINDING = "https://mr-api.osm.lol|client|guest-1"
private val ANSWERS = ChoiceAction.Answers(mapOf("backrest" to "yes"))
private fun receipt(id: Long = 42) = PendingChoice(TaskId(id), state = PendingState.PENDING, answeredAt = Instant.parse("2026-10-11T12:00:00Z"))

private class GuestOps : TaskOps {
    var freshTask = task()
    val calls = mutableListOf<String>()
    val submissions = mutableListOf<ChoiceSubmission>()
    var post: suspend () -> PendingChoice = { receipt() }
    var listed: PendingChoice? = receipt()
    override fun choiceOutcomes(task: Task) = outcomes(task)
    override suspend fun getTask(id: TaskId) = freshTask.also { calls += "get" }
    override suspend fun getChallenge(task: Task) = CHALLENGE
    override suspend fun checkChoice(id: TaskId) = ChoiceEligibility(true, true, null).also { calls += "check" }
    override suspend fun submitChoice(task: Task, submission: ChoiceSubmission): ChoiceResult = error("OSM path forbidden")
    override suspend fun skipTask(id: TaskId): Unit = error("Server skip forbidden")
    override suspend fun submitPendingChoice(task: Task, submission: ChoiceSubmission): PendingChoice {
        calls += "pending"
        submissions += submission
        return post()
    }
    override suspend fun pendingReceipt(id: TaskId): PendingChoice? = listed.also { calls += "list" }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GuestTaskWorkControllerTest {
    @Test fun affirmativeReceiptSavesForLaterWithoutTaskRereadOrOsmCalls() = runTest {
        val ops = GuestOps()
        val store = PendingSaveStore(InMemoryCredentialStore())
        val c = TaskWorkController(ops, Writer(0, false, BINDING), this, StandardTestDispatcher(testScheduler), pendingSaves = store, campaignId = 1)
        c.load(TaskId(42)); advanceUntilIdle()
        c.perform(ANSWERS); advanceUntilIdle()
        val done = c.state.value as TaskScreen.Done
        assertTrue(done.savedForLater)
        assertNull(done.changesetId)
        assertEquals(0, done.task.status?.code)
        assertEquals(listOf("get", "check", "pending"), ops.calls)
        assertNull(store.read())
    }

    @Test fun guestSkipIsLocalAndDoesNotCountAsSave() = runTest {
        val ops = GuestOps()
        val c = TaskWorkController(ops, Writer(0, false, BINDING), this, StandardTestDispatcher(testScheduler))
        c.load(TaskId(42)); advanceUntilIdle()
        ops.calls.clear()
        c.perform(ChoiceAction.Skip); advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Skipped)
        assertTrue(ops.calls.isEmpty())
        assertFalse(c.changed)
    }

    @Test fun lostResponseRelaunchRetainsExactIntentAndOldReceiptCannotProveEditedRevision() = runTest {
        val disk = InMemoryCredentialStore()
        val ops = GuestOps().apply { post = { throw MapRouletteException(ErrorKind.NETWORK) } }
        val first = TaskWorkController(ops, Writer(0, false, BINDING), this, StandardTestDispatcher(testScheduler), pendingSaves = PendingSaveStore(disk), campaignId = 1)
        first.load(TaskId(42)); advanceUntilIdle()
        first.perform(ANSWERS); advanceUntilIdle()
        assertTrue(first.state.value is TaskScreen.CheckAgain)
        val original = PendingSaveStore(disk).read()
        assertEquals(ANSWERS.byQuestion, original?.answers)
        // Even completed-by-self task status is not evidence for a guest save.
        ops.freshTask = task(status = 1, completedBy = 0)
        val nextStore = PendingSaveStore(disk)
        val again = TaskWorkController(ops, Writer(0, false, BINDING), this, StandardTestDispatcher(testScheduler), pendingSaves = nextStore, campaignId = 1)
        again.load(TaskId(42)); advanceUntilIdle()
        assertTrue(again.state.value is TaskScreen.CheckAgain)
        assertEquals(1, ops.submissions.size) // Relaunch never auto-sends.
        again.recheck(); advanceUntilIdle()
        assertTrue((again.state.value as TaskScreen.CheckAgain).message.contains("can't confirm these exact"))
        assertEquals(original, nextStore.read())
        assertEquals(1, ops.submissions.size)
        ops.post = { receipt() }
        again.resendSame(); advanceUntilIdle()
        assertEquals(ops.submissions[0], ops.submissions[1])
        assertTrue((again.state.value as TaskScreen.Done).savedForLater)
        assertNull(nextStore.read())
    }

    @Test fun absentReceiptAndFailedCheckRemainUnresolved() = runTest {
        val ops = GuestOps().apply { post = { error("response lost") }; listed = null }
        val store = PendingSaveStore(InMemoryCredentialStore())
        val c = TaskWorkController(ops, Writer(0, false, BINDING), this, StandardTestDispatcher(testScheduler), pendingSaves = store, campaignId = 1)
        c.load(TaskId(42)); advanceUntilIdle()
        c.perform(ANSWERS); advanceUntilIdle()
        c.recheck(); advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.CheckAgain)
        assertTrue((c.state.value as TaskScreen.CheckAgain).canResend)
        assertNotNull(store.read())
    }

    @Test fun foreignIdentityCannotAdoptOrOverwriteUnresolvedIntent() = runTest {
        val store = PendingSaveStore(InMemoryCredentialStore())
        val original = PendingSaveIntent(BINDING, 1, 42, ANSWERS.byQuestion)
        store.keep(original)
        val ops = GuestOps()
        val c = TaskWorkController(ops, Writer(0, false, "other-guest"), this, StandardTestDispatcher(testScheduler), pendingSaves = store, campaignId = 1)
        c.load(TaskId(42)); advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Answering)
        c.perform(ANSWERS); advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Answering)
        assertTrue(ops.submissions.isEmpty())
        assertEquals(original, store.read())
    }

    @Test fun durableWriteFailurePreventsPost() = runTest {
        val broken = object : CredentialStore {
            override suspend fun read(): String? = null
            override suspend fun write(data: String): Unit = error("disk full")
            override suspend fun clear() {}
        }
        val ops = GuestOps()
        val c = TaskWorkController(ops, Writer(0, false, BINDING), this, StandardTestDispatcher(testScheduler), pendingSaves = PendingSaveStore(broken))
        c.load(TaskId(42)); advanceUntilIdle()
        c.perform(ANSWERS); advanceUntilIdle()
        assertTrue((c.state.value as TaskScreen.Answering).notice!!.contains("No answers were sent"))
        assertTrue(ops.submissions.isEmpty())
    }

    @Test fun discardControllerAfterIdentityChangeNeverShowsLateSuccess() = runTest {
        val response = CompletableDeferred<PendingChoice>()
        val ops = GuestOps().apply { post = { response.await() } }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val c = TaskWorkController(ops, Writer(0, false, BINDING), scope, StandardTestDispatcher(testScheduler), pendingSaves = PendingSaveStore(InMemoryCredentialStore()))
        c.load(TaskId(42)); advanceUntilIdle()
        c.perform(ANSWERS); runCurrent()
        c.cancel()
        response.complete(receipt()); advanceUntilIdle()
        assertTrue(c.state.value is TaskScreen.Submitting)
        scope.cancel()
    }

    @Test fun pendingReceiptLookupWalksAllPagesUsingGuestEndpointOnly() = runTest {
        val paths = mutableListOf<String>()
        val client = MapRouletteClient(transport = Transport { request ->
            paths += request.url
            assertEquals("/api/v2/mobile-guest/pending", URI(request.url).path)
            HttpResponse(200, body = if (paths.size == 1)
                """{"items":[{"taskId":7,"state":"pending","answeredAt":"2026-10-11T12:00:00Z"}],"next":"cursor1"}"""
            else """{"items":[{"taskId":42,"state":"pending","answeredAt":"2026-10-11T12:00:00Z"}]}""")
        }, environment = MapRouletteEnvironment("https://mr-api.osm.lol/api/v2/", true), accessToken = { "guest-token" })
        assertEquals(TaskId(42), ClientTaskOps(client).pendingReceipt(TaskId(42))?.taskId)
        assertEquals(2, paths.size)
        assertTrue(paths[1].contains("after=cursor1"))
    }
    @Test fun clientBridgePostsOnlyPendingEndpointWithCanonicalAnswers() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val client = MapRouletteClient(transport = Transport { request ->
            requests += request
            HttpResponse(200, body = """{"taskId":42,"state":"pending","answeredAt":"2026-10-11T12:00:00Z"}""")
        }, environment = MapRouletteEnvironment("https://mr-api.osm.lol/api/v2/", true), accessToken = { "guest-token" })
        val result = ClientTaskOps(client).submitPendingChoice(task(), ChoiceSubmission.Answers(ANSWERS.byQuestion))
        assertEquals(TaskId(42), result.taskId)
        assertEquals(1, requests.size)
        assertEquals(HttpMethod.POST, requests.single().method)
        assertEquals("/api/v2/task/42/choice/pending", URI(requests.single().url).path)
        assertTrue(requests.single().body!!.contains("backrest"))
    }

    @Test fun definiteGuestRefusalNeverShowsSuccessOrKeepsOfferingResend() = runTest {
        val ops = GuestOps().apply { post = { throw MapRouletteException(ErrorKind.CONFLICT, reason = "task_completed") } }
        val store = PendingSaveStore(InMemoryCredentialStore())
        val c = TaskWorkController(ops, Writer(0, false, BINDING), this, StandardTestDispatcher(testScheduler), pendingSaves = store)
        c.load(TaskId(42)); advanceUntilIdle()
        c.perform(ANSWERS); advanceUntilIdle()
        assertFalse((c.state.value as TaskScreen.CheckAgain).canResend)
        assertNotNull(store.read())
        c.resendSame(); advanceUntilIdle()
        assertEquals(1, ops.submissions.size)
    }

}
