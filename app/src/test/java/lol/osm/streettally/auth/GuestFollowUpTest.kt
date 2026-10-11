package lol.osm.streettally.auth

import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import lol.osm.streettally.AppConfig
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.task.PendingSaveIntent
import lol.osm.streettally.task.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test
import org.maproulette.sdk.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GuestFollowUpTest {
    private fun config() = AppConfig("https://mr-api.osm.lol", "maproulette-ios-choice",
        "lol.osm.maproulette.choice", false, "https://tiles.openfreemap.org/styles/positron", "streettally.osm.lol", true)
    private fun status(count: Int = 2, state: String = "active", email: String = "none") = HttpResponse(200,
        body = """{"guestId":"guest-1","state":"$state","email":"$email","pending":$count,"published":0,"expiresAt":"2026-11-09T18:00:00Z"}""")
    private class Backend : Transport {
        val requests = mutableListOf<HttpRequest>()
        var answer: suspend () -> HttpResponse = { HttpResponse(500) }
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return when (URI(request.url).path) {
                "/oauth/mobile/guest" -> HttpResponse(201, body = """{"guestId":"guest-1","guestSecret":"secret-abcdefghijklmnopqrstuvwxyz"}""")
                "/oauth/mobile/token" -> HttpResponse(200, body = """{"token_type":"Bearer","access_token":"guest-token-abcdefghijklmnopqrstuvwxyz","expires_in":3600,"scope":"guest"}""")
                else -> answer()
            }
        }
    }
    private suspend fun session(backend: Backend, store: CredentialStore = InMemoryCredentialStore(), pending: PendingSaveStore = PendingSaveStore(InMemoryCredentialStore())) =
        AppSession(config(), store, backend, pending).also { it.restore(); it.ensureGuest() }

    @Test fun reopenRefreshesCountAndFailureRetainsConfirmedState() = runTest {
        val backend = Backend()
        val disk = InMemoryCredentialStore()
        val app = session(backend, disk)
        backend.answer = { status() }
        app.refreshGuestStatus()
        backend.answer = { HttpResponse(503) }
        app.refreshGuestStatus()
        assertEquals(GuestState.ACTIVE, app.followUp.value.status?.state)
        assertEquals(2, app.followUp.value.status?.pendingCount)
        assertTrue(app.followUp.value.error!!.contains("doesn't mean they were removed"))
        backend.answer = { status(3) }
        val reopened = session(backend, disk)
        reopened.refreshGuestStatus()
        assertEquals(3, reopened.followUp.value.status?.pendingCount)
        assertNull(reopened.followUp.value.lastLinkEmail)
        assertEquals(1, backend.requests.count { URI(it.url).path == "/oauth/mobile/guest" })
    }

    @Test fun emailUsesPinnedEndpointAndKeepsUnknownIntentOnSuccessAndFailure() = runTest {
        val backend = Backend()
        val pending = PendingSaveStore(InMemoryCredentialStore())
        val app = session(backend, pending = pending)
        val intent = PendingSaveIntent(app.writer()!!.guestBinding!!, 3, 4, mapOf("q" to "yes"))
        pending.keep(intent)
        backend.answer = { status(email = "pending") }
        app.sendFinishLink(" rosa@example.org ")
        assertEquals("rosa@example.org", app.followUp.value.lastLinkEmail)
        assertEquals(GuestEmailState.PENDING, app.followUp.value.status?.email)
        val sent = backend.requests.last()
        assertEquals(HttpMethod.PUT, sent.method)
        assertEquals("/api/v2/mobile-guest/email", URI(sent.url).path)
        assertEquals("""{"email":"rosa@example.org"}""", sent.body)
        assertEquals(intent, pending.read())
        backend.answer = { HttpResponse(429, body = """{"error":"email_rate_limited"}""") }
        try { app.sendFinishLink("new@example.org"); fail("must fail") } catch (_: MapRouletteException) {}
        assertEquals("rosa@example.org", app.followUp.value.lastLinkEmail)
        assertEquals(intent, pending.read())
        assertFalse(backend.requests.any { it.url.contains("changeset") || it.url.contains("choice/pending") })
    }

    @Test fun validationSendsNothingAndConflictDoesNotClaimPublication() = runTest {
        val backend = Backend()
        val app = session(backend)
        val before = backend.requests.size
        try { app.sendFinishLink("bad"); fail("must fail") } catch (_: IllegalArgumentException) {}
        assertEquals(before, backend.requests.size)
        assertEquals("That doesn't look like an email address.", Wording.linkFailed(IllegalArgumentException(), Noun.PLACE))
        assertTrue(Wording.linkFailed(MapRouletteException(ErrorKind.CONFLICT, 409, reason = "nothing_saved"), Noun.PLACE).startsWith("Answer a place first"))
        assertFalse(Wording.linkFailed(MapRouletteException(ErrorKind.CONFLICT, 409), Noun.PLACE).contains("already on the map"))
    }

    @Test fun onlyConfirmedStatusCanBeExpired() = runTest {
        val backend = Backend()
        val app = session(backend)
        backend.answer = { status(0, "expired") }
        app.refreshGuestStatus()
        assertEquals(GuestState.EXPIRED, app.followUp.value.status?.state)
        assertNull(app.followUp.value.error)
    }

    @Test fun oldGenerationReadCannotReplaceCurrentStateAfterCancelledSignIn() = runTest {
        val backend = Backend()
        val app = session(backend)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        backend.answer = { started.complete(Unit); finish.await(); status(99) }
        val job = launch { app.refreshGuestStatus() }
        runCurrent()
        started.await()
        app.signIn { throw IllegalStateException("browser closed") }
        finish.complete(Unit)
        job.join()
        assertNull(app.followUp.value.status)
        assertFalse(app.followUp.value.refreshing)
        assertNotNull(app.state.value.guest)
        assertNull(app.state.value.account)
    }

    @Test fun foreignGuestStatusCannotBecomeConfirmedCountOrExpiry() = runTest {
        val backend = Backend()
        val app = session(backend)
        backend.answer = { status(99, "expired").let { HttpResponse(200, body = it.body.replace("guest-1", "guest-2")) } }
        app.refreshGuestStatus()
        assertNull(app.followUp.value.status)
        assertNotNull(app.followUp.value.error)
        assertFalse(app.followUp.value.refreshing)
    }

    @Test fun emailAndStatusRefreshAreSerializedToKeepNewestDeadlineAndEmailState() = runTest {
        val backend = Backend()
        val app = session(backend)
        val finishRead = CompletableDeferred<Unit>()
        var calls = 0
        backend.answer = {
            calls++
            if (calls == 1) { finishRead.await(); status() } else status(3, email = "pending")
        }
        val read = launch { app.refreshGuestStatus() }
        runCurrent()
        val send = launch { app.sendFinishLink("rosa@example.org") }
        runCurrent()
        assertEquals(1, calls)
        finishRead.complete(Unit)
        read.join()
        send.join()
        assertEquals(3, app.followUp.value.status?.pendingCount)
        assertEquals(GuestEmailState.PENDING, app.followUp.value.status?.email)
    }

    @Test fun refusedGuestTokenDoesNotCreateReplacementAfterRelaunch() = runTest {
        val disk = InMemoryCredentialStore()
        val accessFailure = InMemoryCredentialStore()
        var registrations = 0
        val backend = Transport { request ->
            when (URI(request.url).path) {
                "/oauth/mobile/guest" -> {
                    registrations++
                    HttpResponse(201, body = """{"guestId":"guest-1","guestSecret":"secret-abcdefghijklmnopqrstuvwxyz"}""")
                }
                "/oauth/mobile/token" -> HttpResponse(400, body = """{"error":"invalid_grant"}""")
                else -> error("must not read status without token")
            }
        }
        val app = AppSession(config(), disk, backend, guestAccessFailureStore = accessFailure)
        app.restore()
        app.ensureGuest()
        app.refreshGuestStatus()
        assertNull(app.state.value.guest)
        assertEquals(AppSession.SAVED_ACCESS_UNAVAILABLE, app.state.value.message)
        val reopened = AppSession(config(), disk, backend, guestAccessFailureStore = accessFailure)
        reopened.restore()
        reopened.ensureGuest()
        assertEquals(1, registrations)
        assertNull(reopened.writer())
        assertEquals(AppSession.SAVED_ACCESS_UNAVAILABLE, reopened.state.value.message)
    }

    @Test fun priorConfirmedSaveThenPendingTokenRefusalCannotReplaceGuestOnRelaunch() = refusedPending(false)
    @Test fun cancelledPendingDuringTokenRefusalCannotReplaceGuestOnRelaunch() = refusedPending(true)

    private fun refusedPending(cancel: Boolean) = runTest {
        val disk = InMemoryCredentialStore()
        val evidence = InMemoryCredentialStore()
        val pending = PendingSaveStore(InMemoryCredentialStore())
        var registrations = 0
        var refuseToken = false
        val tokenStarted = CompletableDeferred<Unit>()
        val finishToken = CompletableDeferred<Unit>()
        val backend = Transport { request ->
            when (URI(request.url).path) {
                "/oauth/mobile/guest" -> {
                    registrations++
                    HttpResponse(201, body = """{"guestId":"guest-1","guestSecret":"secret-abcdefghijklmnopqrstuvwxyz"}""")
                }
                "/oauth/mobile/token" -> if (refuseToken) {
                    tokenStarted.complete(Unit)
                    finishToken.await()
                    HttpResponse(400, body = """{"error":"invalid_grant"}""")
                }
                    else HttpResponse(200, body = """{"token_type":"Bearer","access_token":"guest-token-abcdefghijklmnopqrstuvwxyz","expires_in":3600,"scope":"guest"}""")
                "/api/v2/task/42/choice/pending" -> HttpResponse(200, body = """{"taskId":42,"state":"pending","answeredAt":"2026-10-11T12:00:00Z"}""")
                else -> error("unexpected request")
            }
        }
        fun app() = AppSession(config(), disk, backend, pending, evidence)
        fun controller(app: AppSession): TaskWorkController {
            val bridge = ClientTaskOps(app.client())
            val ops = object : TaskOps by bridge {
                override suspend fun getTask(id: TaskId) = task()
                override suspend fun getChallenge(task: Task) = CHALLENGE
                override suspend fun checkChoice(id: TaskId) = ChoiceEligibility(true, true, null)
            }
            return TaskWorkController(ops, app.writer(), this, StandardTestDispatcher(testScheduler), pendingSaves = pending, campaignId = 1)
        }
        val first = app().also { it.restore(); it.ensureGuest() }
        val saved = controller(first)
        saved.load(TaskId(42)); advanceUntilIdle()
        saved.perform(ChoiceAction.Answers(mapOf("backrest" to "yes"))); advanceUntilIdle()
        assertTrue((saved.state.value as TaskScreen.Done).savedForLater)
        assertNull(pending.read())
        refuseToken = true
        val second = app().also { it.restore(); it.ensureGuest() }
        val rejected = controller(second)
        rejected.load(TaskId(42)); advanceUntilIdle()
        rejected.perform(ChoiceAction.Answers(mapOf("backrest" to "no"))); runCurrent()
        tokenStarted.await()
        if (cancel) rejected.cancel()
        finishToken.complete(Unit)
        advanceUntilIdle()
        assertTrue(if (cancel) rejected.state.value is TaskScreen.Submitting else rejected.state.value is TaskScreen.Answering)
        assertNull(pending.read()) // First definite refusal releases only this new intent.
        val reopened = app().also { it.restore(); it.ensureGuest() }
        assertEquals(1, registrations)
        assertNull(reopened.writer())
        assertEquals(AppSession.SAVED_ACCESS_UNAVAILABLE, reopened.state.value.message)
    }

    @Test fun cancelledStatusOrEmailDuringRefusedTokenCannotReplaceGuestOnRelaunch() = runTest {
        for (email in listOf(false, true)) {
            val disk = InMemoryCredentialStore()
            val evidence = InMemoryCredentialStore()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var registrations = 0
            val backend = Transport { request ->
                when (URI(request.url).path) {
                    "/oauth/mobile/guest" -> {
                        registrations++
                        HttpResponse(201, body = """{"guestId":"guest-1","guestSecret":"secret-abcdefghijklmnopqrstuvwxyz"}""")
                    }
                    "/oauth/mobile/token" -> { started.complete(Unit); finish.await(); HttpResponse(400, body = """{"error":"invalid_grant"}""") }
                    else -> error("no operation after token refusal")
                }
            }
            val app = AppSession(config(), disk, backend, guestAccessFailureStore = evidence)
            app.restore(); app.ensureGuest()
            val operation = launch {
                try { if (email) app.sendFinishLink("rosa@example.org") else app.refreshGuestStatus() }
                catch (_: MapRouletteException) { /* SDK may report refusal from noncancellable token work. */ }
            }
            runCurrent(); started.await()
            operation.cancel()
            finish.complete(Unit)
            operation.join()
            val reopened = AppSession(config(), disk, backend, guestAccessFailureStore = evidence)
            reopened.restore(); reopened.ensureGuest()
            assertEquals(1, registrations)
            assertNull(reopened.writer())
            assertEquals(AppSession.SAVED_ACCESS_UNAVAILABLE, reopened.state.value.message)
        }
    }

    @Test fun evidenceStorageFailureExposesNoWriterAndRetriesSameGuest() = runTest {
        val disk = InMemoryCredentialStore()
        val evidenceDisk = InMemoryCredentialStore()
        var failed = true
        val evidence = object : CredentialStore {
            override suspend fun read() = evidenceDisk.read()
            override suspend fun write(data: String) { if (failed) error("disk full"); evidenceDisk.write(data) }
            override suspend fun clear() = evidenceDisk.clear()
        }
        val backend = Backend()
        val app = AppSession(config(), disk, backend, guestAccessFailureStore = evidence)
        app.restore(); app.ensureGuest()
        assertNull(app.writer())
        assertNull(app.state.value.guest)
        backend.answer = { status() }
        app.client().getGuestStatus() // Reads cannot use the SDK guest before evidence persists.
        assertFalse(backend.requests.any { URI(it.url).path == "/oauth/mobile/token" })
        assertFalse(backend.requests.last().headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        failed = false
        app.ensureGuest()
        assertNotNull(app.writer())
        assertEquals(1, backend.requests.count { URI(it.url).path == "/oauth/mobile/guest" })
        val reopened = AppSession(config(), disk, backend, guestAccessFailureStore = evidence)
        reopened.restore(); reopened.ensureGuest()
        assertEquals("guest-1", reopened.state.value.guest?.guestId)
        assertNull(reopened.state.value.message)
        assertNotNull(reopened.writer())
    }

    @Test fun withoutLocationSelectionNeverSaysNearest() {
        assertEquals("Choose a place", Wording.nearest(Noun.PLACE, null, false))
        assertEquals("Choose another place", Wording.next(Noun.PLACE, null, false))
    }
    @OptIn(LowLevelTaskLifecycle::class)
    @Test fun stageGuestSaveLostResponseRecoveryAndStatusNeverCallAuthenticatedMutations() = runTest {
        val backend = Backend()
        val disk = InMemoryCredentialStore()
        val cfg = config().copy(baseUrl = "https://mr-stage.osm.lol", allowLiveWrites = false)
        val app = AppSession(cfg, disk, backend)
        app.restore(); app.ensureGuest()
        assertTrue(app.writer()!!.isGuest)
        assertFalse(app.writer()!!.canEditOsm)
        assertEquals(setOf("tasks:read"), app.requestedScopes)
        var posts = 0
        backend.answer = {
            val request = backend.requests.last()
            when (URI(request.url).path) {
                "/api/v2/task/42/choice/pending" -> {
                    assertEquals(HttpMethod.POST, request.method)
                    posts++
                    if (posts == 1) throw MapRouletteException(ErrorKind.NETWORK)
                    HttpResponse(200, body = """{"taskId":42,"state":"pending","answeredAt":"2026-10-11T12:00:00Z"}""")
                }
                "/api/v2/mobile-guest/pending" -> HttpResponse(200, body = """{"items":[]}""")
                "/api/v2/mobile-guest/me" -> status(1)
                else -> error("Unexpected guest route")
            }
        }
        val client = app.client()
        val bridge = ClientTaskOps(client)
        val pending = PendingSaveStore(InMemoryCredentialStore())
        val ops = object : TaskOps by bridge {
            override suspend fun getTask(id: TaskId) = task()
            override suspend fun getChallenge(task: Task) = CHALLENGE
            override suspend fun checkChoice(id: TaskId) = ChoiceEligibility(true, true, null)
        }
        val first = TaskWorkController(ops, app.writer(), this, StandardTestDispatcher(testScheduler), pendingSaves = pending, campaignId = 1)
        first.load(TaskId(42)); advanceUntilIdle()
        first.perform(ChoiceAction.Answers(mapOf("backrest" to "yes"))); advanceUntilIdle()
        assertTrue(first.state.value is TaskScreen.CheckAgain)
        assertNotNull(pending.read())
        // The same durable guest is restored and the pending intent is checked before resending.
        val reopened = AppSession(cfg, disk, backend)
        reopened.restore(); reopened.ensureGuest()
        assertEquals(app.writer(), reopened.writer())
        val recoveredBridge = ClientTaskOps(reopened.client())
        val recoveredOps = object : TaskOps by recoveredBridge {
            override suspend fun getTask(id: TaskId) = task()
            override suspend fun getChallenge(task: Task) = CHALLENGE
            override suspend fun checkChoice(id: TaskId) = ChoiceEligibility(true, true, null)
        }
        val again = TaskWorkController(recoveredOps, reopened.writer(), this, StandardTestDispatcher(testScheduler), pendingSaves = pending, campaignId = 1)
        again.load(TaskId(42)); advanceUntilIdle()
        again.recheck(); advanceUntilIdle()
        assertEquals(1, posts)
        again.resendSame(); advanceUntilIdle()
        assertTrue((again.state.value as TaskScreen.Done).savedForLater)
        assertNull(pending.read())
        reopened.refreshGuestStatus()
        assertEquals(1, reopened.followUp.value.status?.pendingCount)
        val before = backend.requests.size
        try { reopened.client().skipTask(TaskId(42)); fail("Guest lifecycle must be denied") }
        catch (e: MapRouletteException) { assertEquals(ErrorKind.AUTHENTICATION, e.kind) }
        try { reopened.client().submitChoice(task(), ChoiceSubmission.Answers(mapOf("backrest" to "yes"))); fail("Guest OSM writes must be denied") }
        catch (e: MapRouletteException) { assertEquals(ErrorKind.AUTHENTICATION, e.kind) }
        for (call in listOf<suspend () -> Unit>(
            { reopened.client().startTask(TaskId(42)); Unit },
            { reopened.client().refreshTaskLock(TaskId(42)); Unit },
            { reopened.client().releaseTask(TaskId(42)) },
        )) {
            try { call(); fail("GET lifecycle mutations must be denied") }
            catch (e: MapRouletteException) { assertEquals(ErrorKind.AUTHENTICATION, e.kind) }
        }
        assertEquals(before, backend.requests.size)
        val disabled = AppSession(cfg.copy(guestEnabled = false), disk, backend)
        disabled.restore(); disabled.ensureGuest()
        assertNull(disabled.writer())
        try { disabled.client().submitPendingChoice(task(), ChoiceSubmission.Answers(mapOf("backrest" to "yes"))); fail("Disabled pending save") }
        catch (_: IllegalStateException) {}
        try { disabled.client().getGuestStatus(); fail("Disabled guest status") }
        catch (e: MapRouletteException) { assertEquals(ErrorKind.AUTHENTICATION, e.kind) }
        assertEquals(before, backend.requests.size)
        val paths = backend.requests.map { URI(it.url).path }
        assertEquals(1, paths.count { it == "/oauth/mobile/guest" })
        assertEquals(2, paths.count { it == "/api/v2/task/42/choice/pending" })
        assertTrue(paths.all { it in setOf("/oauth/mobile/guest", "/oauth/mobile/token", "/api/v2/task/42/choice/pending", "/api/v2/mobile-guest/pending", "/api/v2/mobile-guest/me") })
    }

}
