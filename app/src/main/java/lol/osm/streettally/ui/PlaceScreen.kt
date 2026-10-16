package lol.osm.streettally.ui

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.CheckedLog
import lol.osm.streettally.campaign.Footprint
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.LocatedTask
import lol.osm.streettally.campaign.MovedSpot
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.Places
import lol.osm.streettally.campaign.TaskReport
import lol.osm.streettally.campaign.WhichOne
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.location.LocateResult
import lol.osm.streettally.location.Locator
import lol.osm.streettally.task.ChoiceAction
import lol.osm.streettally.task.ChoiceForm
import lol.osm.streettally.task.ClientTaskOps
import lol.osm.streettally.task.TaskScreen
import lol.osm.streettally.task.TaskText
import lol.osm.streettally.task.TaskWorkController
import lol.osm.streettally.task.isReport
import lol.osm.streettally.task.needsLook
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.ChoiceQuestion
import org.maproulette.sdk.MovedLocation
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskWork

/** One place, one question per screen, sized for the street. Late locking: viewing never locks;
 * saving locks, submits and releases (TaskWorkController). Mirrors the iOS TaskView without the
 * "Show OSM details" extras. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceScreen(
    session: AppSession,
    checked: CheckedLog,
    campaignId: Long,
    firstTaskId: Long,
    /** The campaign's places, for "Next nearest". */
    candidates: List<LocatedTask>,
    noun: Noun,
    /** Where the person was when they opened the first place; not live, so shown only for it. */
    origin: LatLon?,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    /** Campaign preview (design/preview-mode.md): places "saved" in this visit; null when not previewing. */
    previewSaved: MutableSet<Long>? = null,
    onExitPreview: () -> Unit = {},
    /** Other people's not-here and moved reports, by task id (parity row 4; empty until the server read). */
    reports: Map<Long, TaskReport> = emptyMap(),
) {
    val previewing = previewSaved != null
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    // The place, question and answers on screen survive recreation (rotation, dark mode, font size):
    // people tilt their phones mid-answer (Ada, #36). The answers are replayed into the new controller.
    var taskId by rememberSaveable { mutableStateOf(firstTaskId) }
    val visited = remember { mutableSetOf(firstTaskId) }
    var generation by remember { mutableIntStateOf(state.generation) }
    var controller by remember { mutableStateOf(newController(session, scope, noun, previewing)) }
    var switchNotice by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    // The question on screen; questions.size is the review step.
    var step by rememberSaveable { mutableIntStateOf(0) }
    var notSure by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var answers by rememberSaveable { mutableStateOf(HashMap<String, String>()) }
    var replayed by remember { mutableStateOf(false) }
    var counted by remember { mutableStateOf(false) }
    var showProblems by remember { mutableStateOf(false) }
    // "It's moved": picking the spot on the map, then the report waiting for Done.
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<LatLon?>(null) }
    var pickedFromHere by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ChoiceAction?>(null) }
    // "I can't tell which one" (which-one-spec.md F): its own thanks line once saved.
    var cantTell by remember { mutableStateOf(false) }
    // What the task map senses, for the direction line; and how many neighbours its first frame shows.
    val sensed = remember { Sensed() }
    // The aerial photo stays on from place to place until turned off (spec D).
    var aerial by rememberSaveable { mutableStateOf(false) }
    var inFrame by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    fun resetReport() {
        picking = false
        picked = null
        pickedFromHere = false
        report = null
    }

    LaunchedEffect(Unit) {
        // Registering a guest changes the session generation; do it before binding the controller.
        // Never in preview: that would leave a guest behind (design/preview-mode.md §2).
        val bound = generation
        if (!previewing && session.canAnswerAsGuest) session.ensureGuest()
        // A new generation (the guest just registered) rebinds and loads in the effect below, once.
        if (session.state.value.generation != bound) return@LaunchedEffect
        controller.load(TaskId(taskId))
    }
    // Account switch or sign-out: discard the old account's controller and any late result.
    LaunchedEffect(state.generation) {
        if (state.generation == generation) return@LaunchedEffect
        generation = state.generation
        val old = controller
        val interrupted = old.busy || old.state.value is TaskScreen.CheckAgain
        old.cancel()
        confirmation = null
        controller = newController(session, scope, noun, previewing)
        switchNotice = if (interrupted) {
            "Your sign-in changed while your answers were being saved, so we can't tell whether they were."
        } else null
        step = 0
        notSure = emptySet()
        answers = HashMap()
        resetReport()
        controller.load(TaskId(taskId))
    }
    val c = controller
    val screen by c.state.collectAsState()
    // After recreation: put the saved answers back once this place's questions are on screen.
    LaunchedEffect(c, screen) {
        val answering = screen as? TaskScreen.Answering ?: return@LaunchedEffect
        if (replayed || answering.task.id.value != taskId) return@LaunchedEffect
        replayed = true
        answers.forEach { (q, o) -> c.select(q, o) }
        step = step.coerceAtMost(answering.form.work.questions.size)
    }
    // A preview save counts only in this visit's preview (§4.4).
    LaunchedEffect(c, screen) {
        (screen as? TaskScreen.PreviewSaved)?.let { previewSaved?.add(it.task.id.value) }
    }
    // Count a place this person saved (not skipped, not someone else's result) once.
    LaunchedEffect(c, screen) {
        val done = screen as? TaskScreen.Done
        if (done != null && !done.byOther && !counted) {
            counted = true
            checked.record(campaignId, done.task.id.value, look = done.action.needsLook, report = TaskReport.of(done.action))
        }
    }
    // Signed in here: the sign-in and claim notes show on this screen too. No sheet opens after the
    // first save; the confirmation and its note say it, and the campaign's banner opens the sheet
    // (handback-copy.md §1, "No sheet after the first save").
    var accountNote by remember { mutableStateOf(false) }

    val here: LatLon? = loadedTask(screen)?.let { Places.position(it) }
        ?: candidates.firstOrNull { it.id == taskId }?.position
    val next = Places.nearest(here, candidates, visited + checked.done(campaignId) + checked.reported(campaignId) + reports.keys)

    fun open(place: LocatedTask) {
        if (c.busy) return
        switchNotice = null
        taskId = place.id
        visited += place.id
        counted = false
        cantTell = false
        inFrame = 0
        step = 0
        notSure = emptySet()
        answers = HashMap()
        resetReport()
        controller = newController(session, scope, noun, previewing)
        controller.load(TaskId(place.id))
    }

    BackHandler(enabled = c.busy) { /* Back waits while a write is in flight (normally a few seconds). */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !c.busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Wording.backToAll(noun))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (previewing) PreviewBanner(published = false, onExit = onExitPreview, onOpenForReal = {})
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                loadedTask(screen)?.let { task ->
                    // The close-up map of the place and its neighbours, then the live direction line
                    // (design/which-one-spec.md A to C). The moved flow and its review draw their own
                    // map, so it steps aside there.
                    val position = Places.position(task)
                    val live = position != null && !picking && report == null
                    if (live && position != null) {
                        val neighbours = WhichOne.neighbours(
                            task.id.value, candidates, checked.done(campaignId),
                            checked.reported(campaignId) + reports.keys,
                        )
                        val height = maxOf(MIN_MAP_HEIGHT, (LocalConfiguration.current.screenHeightDp * MAP_SHARE).dp)
                        // Keyed: Next nearest reuses this screen with a new map.
                        key(task.id.value) {
                            TaskMap(
                                session.config.mapStyleUrl, position, neighbours,
                                Wording.taskMapLabel(Wording.placeName(task, noun), noun), sensed,
                                onNeighboursInFrame = { inFrame = it },
                                modifier = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)),
                                aerial = aerial,
                                onAerial = { aerial = it },
                                kind = Footprint.kind(task),
                            )
                        }
                        key(task.id.value) { DirectionLine(sensed, position, noun) }
                        if (inFrame > 0) {
                            Text(Wording.otherDots(noun), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // The live line replaces the "from you" line once location is on.
                    val fromYou = if (task.id.value == firstTaskId && !(live && Locator.permitted(context))) {
                        Places.fromYouText(origin, position)
                    } else null
                    // "I can't tell which one" (spec F): the can't-check outcome, offered on every question,
                    // as a link under the dots note (or the direction line), so it stays above the fold.
                    (screen as? TaskScreen.Answering)?.takeIf {
                        report == null && !picking && c.canEditOsm && step < it.form.work.questions.size
                    }?.let { s ->
                        s.form.outcomes.firstOrNull { it.id == ChoiceOutcome.TOO_HARD_ID }
                            ?.let { ChoiceAction.Outcome(it) }?.takeIf { c.offers(s.task, s.form, it) }
                    }?.let { action ->
                        CantTellLink(Wording.cantTell(noun)) {
                            switchNotice = null
                            cantTell = true
                            c.perform(action)
                        }
                    }
                    Header(task, noun, fromYou)
                    task.lockedBy?.let { if (it != state.account?.userId) Banner("Someone else may be checking this ${noun.one} right now. You can still answer.") }
                    // Someone else's report (not-here-moved.md §4). Whoever reported it here sees their own saved line instead.
                    val id = task.id.value
                    if (LocatedTask.of(task).isOpen && id !in checked.done(campaignId) && id !in checked.reported(campaignId)) {
                        TaskReport.banner(reports[id], Places.position(task), noun)?.let { Banner(it) }
                    }
                }
                if (screen !is TaskScreen.Loading) switchNotice?.let { Banner(it) }
                if (accountNote) AccountNote(state)

                val nextButtons: @Composable (Boolean) -> Unit = { prominent ->
                    NextButtons(
                        noun, next?.let { Wording.next(noun, Places.directionText(here, it.position)) }, prominent,
                        onNext = { next?.let { open(it) } }, onBack = onBack,
                    )
                }

                when (val s = screen) {
                    TaskScreen.Loading -> Busy("Loading…")
                    is TaskScreen.LoadFailed -> {
                        Text(s.message)
                        BigButton("Try again") { c.load(TaskId(taskId)) }
                        BackButton(noun, onBack)
                    }
                    is TaskScreen.NotAvailable -> {
                        Text(Wording.cannotAnswer(noun), style = MaterialTheme.typography.titleMedium)
                        nextButtons(true)
                    }
                    is TaskScreen.Preview -> Preview(s.work, noun, session, state.account != null, onSignIn) { BackButton(noun, onBack) }
                    is TaskScreen.CheckFailed -> if (s.paused) {
                        // The alert below says why; OK goes back to the campaign.
                        Text(Wording.writesPausedAtOpen(noun))
                    } else {
                        Text(Wording.checkFailed(noun))
                        BigButton("Try again") { c.load(TaskId(taskId)) }
                        nextButtons(false)
                    }
                    is TaskScreen.NoLongerNeeded -> {
                        Text(Wording.alreadyChecked(noun), style = MaterialTheme.typography.titleMedium)
                        nextButtons(true)
                    }
                    is TaskScreen.Answering -> {
                        s.notice?.let { Banner(it) }
                        val mapped = Places.position(s.task)
                        val pending = report
                        if (pending != null) {
                            ReportReview(pending, mapped, noun, session.config.mapStyleUrl, previewing, onDone = {
                                resetReport()
                                switchNotice = null
                                c.perform(pending)
                            }, onBack = {
                                report = null
                                picking = pending is ChoiceAction.Moved
                            })
                        } else if (picking && mapped != null) {
                            MovedPicker(
                                mapped, picked, pickedFromHere, noun, session.config.mapStyleUrl,
                                onPick = { spot, fromHere ->
                                    picked = spot
                                    pickedFromHere = fromHere
                                },
                                onSave = { spot ->
                                    val source = if (pickedFromHere) MovedLocation.Source.HERE else MovedLocation.Source.TAP
                                    report = ChoiceAction.Moved(MovedLocation(spot.lat, spot.lon, source))
                                    picking = false
                                },
                                onCancel = { resetReport() },
                            )
                        } else if (!c.canEditOsm) {
                            Text("This sign-in can't save answers to OpenStreetMap.")
                            BigButton("Sign in again", onClick = onSignIn)
                        } else {
                            val questions = s.form.work.questions
                            if (step < questions.size) {
                                Question(
                                    questions[step], step, questions.size,
                                    selected = s.form.answers[questions[step].id], notSure = questions[step].id in notSure,
                                    onAnswer = { optionId ->
                                        val id = questions[step].id
                                        c.select(id, optionId)
                                        answers = HashMap(answers).apply { if (optionId == null) remove(id) else put(id, optionId) }
                                        notSure = if (optionId == null) notSure + id else notSure - id
                                        step += 1
                                    },
                                    onPrevious = { step -= 1 },
                                )
                            } else {
                                Review(c, s.task, s.form, noun, previewing, onEdit = { step = it })
                            }
                        }
                        if (report == null && !picking) Problems(
                            c, s.task, s.form, noun, showProblems, { showProblems = it },
                            canMove = Places.position(s.task) != null,
                            onNotHere = { report = ChoiceAction.Outcome(ChoiceOutcome.NOT_HERE) },
                            onMoved = { picking = true },
                        ) { action ->
                            switchNotice = null
                            val outcome = (action as? ChoiceAction.Outcome)?.outcome
                            val me = state.account?.userId
                            if (outcome != null && outcome.deletesElement && me != null) {
                                confirmation = Confirmation(
                                    "Delete it from OpenStreetMap?",
                                    TaskText.outcomeSummary(
                                        outcome, s.form.work.element, s.task.id.value, me, null,
                                        notDeletable = outcome.id in s.form.notDeletable,
                                    ),
                                    "Delete", c,
                                ) { it.perform(action) }
                            } else {
                                c.perform(action)
                            }
                        }
                    }
                    is TaskScreen.Submitting -> Busy("Saving…")
                    is TaskScreen.PreviewSaved -> {
                        val realLine = when {
                            s.action is ChoiceAction.Moved -> Wording.REPORTED_MOVED
                            s.action.isReport -> Wording.REPORTED_NOT_HERE
                            else -> Wording.saved((previewSaved.orEmpty() + s.task.id.value).size, noun)
                        }
                        Text(Wording.PREVIEW_NOT_SAVED, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(Wording.previewWouldSay(realLine))
                        nextButtons(true)
                    }
                    is TaskScreen.Done -> {
                        if (s.byOther) {
                            Text(Wording.checkedByOtherFirst(noun), style = MaterialTheme.typography.titleMedium)
                        } else if (s.action.isReport) {
                            ReportSaved(moved = s.action is ChoiceAction.Moved)
                        } else if (cantTell) {
                            Text(Wording.cantTellThanks(noun), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        } else {
                            // Counted once the effect above has recorded it; reading [counted] recomposes then.
                            val today = checked.today(campaignId) + if (counted) 0 else 1
                            Text(
                                "✓ " + if (s.savedForLater) Wording.savedForLater(today, noun) else Wording.saved(today, noun),
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            )
                            // Only the first few times: by then the participant knows.
                            if (s.savedForLater && today <= 3) {
                                Text(Wording.SAVED_FOR_LATER_NOTE, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        nextButtons(true)
                    }
                    is TaskScreen.Skipped -> {
                        Text(
                            if (s.uncertain) "The connection dropped, so we're not sure the skip went through. You don't need to do anything."
                            else "Skipped. It stays open for someone else.",
                        )
                        nextButtons(true)
                    }
                    is TaskScreen.TakenByOther -> {
                        Text(Wording.takenByOther(noun))
                        nextButtons(true)
                    }
                    is TaskScreen.StaleOwnLock -> {
                        Text("Your last try on this ${noun.one} is still being processed. Wait a moment, then try again. Nothing was changed.")
                        BigButton("Try again") { c.perform(s.action) }
                        BackButton(noun, onBack)
                    }
                    is TaskScreen.CheckAgain -> if (s.checking) {
                        Busy("Checking…")
                    } else {
                        Text("The connection dropped, so we're not sure your answers were saved. Tap Check again to find out. They weren't sent twice.")
                        Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BigButton("Check again", prominent = true) { c.recheck() }
                        if (s.canResend) {
                            BigButton("Send the same answers again") {
                                confirmation = Confirmation(
                                    "Send the same answers again?",
                                    "This finishes your earlier try. Your answers are never saved twice.",
                                    "Send again", c,
                                ) { it.resendSame() }
                            }
                        }
                        BackButton(noun, onBack)
                    }
                    is TaskScreen.GuestSignIn -> {
                        Text(Wording.guestSignIn(noun), style = MaterialTheme.typography.titleMedium)
                        Text(Wording.guestSignInDetail(noun))
                        // Most guests have no account: moving on comes first.
                        next?.let { BigButton(Wording.next(noun, Places.directionText(here, it.position)), prominent = true) { open(it) } }
                            ?: Text(Wording.lastOne(noun), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BigButton("Sign in with OpenStreetMap", onClick = { accountNote = true; onSignIn() })
                        Text(ACCOUNT_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BackButton(noun, onBack)
                    }
                    is TaskScreen.SignInRequired -> {
                        Text(s.reason)
                        BigButton("Sign in again", onClick = onSignIn)
                        BackButton(noun, onBack)
                    }
                    is TaskScreen.ElementInUse -> {
                        Text("OpenStreetMap wouldn't delete this ${noun.one} because something else uses it. Nothing was changed.")
                        val me = state.account?.userId
                        val plain = TaskWorkController.withoutDeletion(s.outcome)
                        if (plain != null && me != null) {
                            BigButton("Record “${s.outcome.label}” without deleting") {
                                confirmation = Confirmation(
                                    "Record “${s.outcome.label}” without deleting?",
                                    TaskText.outcomeSummary(plain, s.form.work.element, s.task.id.value, me, null, notDeletable = true),
                                    "Confirm", c,
                                ) { it.sendWithoutDeletion() }
                            }
                        }
                        BackButton(noun, onBack)
                    }
                }
            }
        }
    }

    if ((screen as? TaskScreen.CheckFailed)?.paused == true) {
        AlertDialog(
            onDismissRequest = onBack,
            text = { Text(Wording.writesPausedAtOpen(noun)) },
            confirmButton = { TextButton(onClick = onBack) { Text("OK") } },
        )
    }
    if ((screen as? TaskScreen.Answering)?.paused == true) {
        AlertDialog(
            onDismissRequest = { controller.dismissPaused() },
            text = { Text(Wording.WRITES_PAUSED) },
            confirmButton = { TextButton(onClick = { controller.dismissPaused() }) { Text("OK") } },
        )
    }

    confirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(pending.title) },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    // Bound to the controller that showed it: never write as a later account.
                    if (controller === pending.owner) pending.run(pending.owner)
                }) { Text(pending.confirm) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

/** A confirmation bound to the controller that showed it. */
private class Confirmation(
    val title: String,
    val message: String,
    val confirm: String,
    val owner: TaskWorkController,
    val run: (TaskWorkController) -> Unit,
)

/** A controller is bound to one account's client; results of a replaced one are never shown. */
private fun newController(session: AppSession, scope: CoroutineScope, noun: Noun, previewing: Boolean): TaskWorkController =
    TaskWorkController(ClientTaskOps(session.client()), session.writer(), scope, noun = noun, previewing = previewing)

private fun loadedTask(screen: TaskScreen): Task? = when (screen) {
    TaskScreen.Loading, is TaskScreen.LoadFailed -> null
    is TaskScreen.NotAvailable -> screen.task
    is TaskScreen.Preview -> screen.task
    is TaskScreen.PreviewSaved -> screen.task
    is TaskScreen.NoLongerNeeded -> screen.task
    is TaskScreen.CheckFailed -> screen.task
    is TaskScreen.Answering -> screen.task
    is TaskScreen.Submitting -> screen.task
    is TaskScreen.Done -> screen.task
    is TaskScreen.Skipped -> screen.task
    is TaskScreen.TakenByOther -> screen.task
    is TaskScreen.StaleOwnLock -> screen.task
    is TaskScreen.CheckAgain -> screen.task
    is TaskScreen.SignInRequired -> screen.task
    is TaskScreen.GuestSignIn -> screen.task
    is TaskScreen.ElementInUse -> screen.task
}

@Composable
internal fun Header(task: Task, noun: Noun, fromYou: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Wording.placeName(task, noun), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        fromYou?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Question(
    q: ChoiceQuestion,
    index: Int,
    count: Int,
    selected: String?,
    notSure: Boolean,
    onAnswer: (String?) -> Unit,
    onPrevious: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text("Question ${index + 1} of $count", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { (index + 1f) / count }, modifier = Modifier.fillMaxWidth())
    }
    Text(q.prompt, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    q.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
    q.options.forEach { option ->
        AnswerButton(option.label, option.description, selected == option.id) { onAnswer(option.id) }
    }
    AnswerButton(Wording.NOT_SURE, null, notSure) { onAnswer(null) }
    if (index > 0) TextButton(onClick = onPrevious) { Text("‹ Previous question") }
}

/** "I can't tell which one" as a plain link at the start edge, 48 dp tall for touch. */
@Composable
private fun CantTellLink(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(label, textDecoration = TextDecoration.Underline) }
}

/** The task map takes this share of the screen height, and at least [MIN_MAP_HEIGHT] (spec A). */
private const val MAP_SHARE = 0.35f
private val MIN_MAP_HEIGHT = 220.dp

/** Where the place is from you, under the task map (which-one-spec.md C): recomputed once a second
 * from what the map senses. TalkBack hears it again only when its category changes (the rule, side or
 * compass point, not the number), at most every 5 s. With location denied or off, a line to turn it
 * on and a link to the app's settings (rule 1); nothing before the first fix. Checked again on
 * return, so coming back from Settings with location on starts the line. */
@Composable
private fun DirectionLine(sensed: Sensed, target: LatLon, noun: Noun) {
    val context = LocalContext.current
    val units = remember { WhichOne.units() }
    var line by remember { mutableStateOf<WhichOne.Line?>(null) }
    var announced by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(locationOn(context)) }
    LifecycleResumeEffect(Unit) {
        on = locationOn(context)
        onPauseOrDispose {}
    }
    // The phone's location switch when that is what's off, else the app's own page (permissions).
    val settings = if (Locator.permitted(context)) {
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    if (!on) {
        Column {
            Text(Wording.turnOnLocation(noun), style = MaterialTheme.typography.bodyLarge)
            TextButton(
                onClick = { context.startActivity(settings) },
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(Wording.OPEN_SETTINGS, textDecoration = TextDecoration.Underline) }
        }
        return
    }
    LaunchedEffect(Unit) {
        var lastAnnounced = 0L
        var category: String? = null
        while (isActive) {
            val next = WhichOne.line(sensed.you, sensed.accuracyM, sensed.heading, target, noun, units, line?.side)
            if (next != line) line = next
            val now = System.currentTimeMillis()
            if (next != null && next.category != category && now - lastAnnounced >= ANNOUNCE_MS) {
                category = next.category
                announced = next.text
                lastAnnounced = now
            }
            delay(LINE_MS)
        }
    }
    val text = line?.text ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = announced.ifEmpty { text }
            liveRegion = LiveRegionMode.Polite
        },
    )
}

/** Location permitted and the phone's location switched on. */
private fun locationOn(context: Context): Boolean {
    val manager = context.getSystemService(LocationManager::class.java)
    return Locator.permitted(context) && manager != null && LocationManagerCompat.isLocationEnabled(manager)
}

private const val LINE_MS = 1_000L
private const val ANNOUNCE_MS = 5_000L

@Composable
private fun Review(c: TaskWorkController, task: Task, form: ChoiceForm, noun: Noun, previewing: Boolean, onEdit: (Int) -> Unit) {
    Text("Check your answers", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    form.work.questions.forEachIndexed { index, q ->
        val answer = q.options.firstOrNull { it.id == form.answers[q.id] }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Change this answer") { onEdit(index) },
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(q.prompt)
                    Text(
                        answer?.label ?: Wording.NOT_SURE_SHORT,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (answer == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
                Text("Change", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (form.answers.isEmpty()) {
        Text(Wording.nothingAnswered(noun))
        if (c.offers(task, form, ChoiceAction.Skip)) BigButton(Wording.skip(noun)) { c.perform(ChoiceAction.Skip) }
    } else {
        if (form.answers.size < form.work.questions.size) {
            Text(Wording.notSureCloses(noun), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Answering is the intent: no confirmation dialog for the normal case.
        val action = ChoiceAction.Answers(form.answers)
        BigButton(if (previewing) Wording.DONE_PREVIEW else Wording.DONE, prominent = true, enabled = c.offers(task, form, action)) {
            c.perform(action)
        }
    }
}

/** "Something's wrong here" (design/not-here-moved.md §2), least consequence first: Skip,
 * "I can't check this", "It's not here", "It's moved", then the campaign's own outcomes. */
@Composable
private fun Problems(
    c: TaskWorkController,
    task: Task,
    form: ChoiceForm,
    noun: Noun,
    open: Boolean,
    setOpen: (Boolean) -> Unit,
    /** The place has a position to measure a move from. */
    canMove: Boolean,
    onNotHere: () -> Unit,
    onMoved: () -> Unit,
    choose: (ChoiceAction) -> Unit,
) {
    val outcomes = form.outcomes.filter { c.offers(task, form, ChoiceAction.Outcome(it)) }
    val canSkip = c.offers(task, form, ChoiceAction.Skip)
    val notHere = c.offers(task, form, ChoiceAction.Outcome(ChoiceOutcome.NOT_HERE))
    HorizontalDivider()
    TextButton(onClick = { setOpen(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(Wording.SOMETHING_WRONG)
    }
    if (!open) return
    fun pick(run: () -> Unit) {
        setOpen(false)
        run()
    }
    AlertDialog(
        onDismissRequest = { setOpen(false) },
        title = { Text(Wording.SOMETHING_WRONG) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProblemsList(
                    noun, canSkip, outcomes, notHere, canMove,
                    onChoose = { action -> pick { choose(action) } },
                    onNotHere = { pick(onNotHere) },
                    onMoved = { pick(onMoved) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { setOpen(false) }) { Text("Cancel") } },
    )
}

/** The rows of "Something's wrong here": [outcomes] are the campaign's, already filtered to the
 * ones offered. */
@Composable
internal fun ProblemsList(
    noun: Noun,
    canSkip: Boolean,
    outcomes: List<ChoiceOutcome>,
    notHere: Boolean,
    canMove: Boolean,
    onChoose: (ChoiceAction) -> Unit,
    onNotHere: () -> Unit,
    onMoved: () -> Unit,
) {
    Text(Wording.PROBLEMS_MESSAGE)
    if (canSkip) ProblemRow(Wording.skip(noun), Wording.SKIP_EXPLAINED) { onChoose(ChoiceAction.Skip) }
    outcomes.firstOrNull { it.id == ChoiceOutcome.TOO_HARD_ID }?.let { tooHard ->
        ProblemRow(Wording.cantCheck(noun), Wording.CANT_CHECK_EXPLAINED) { onChoose(ChoiceAction.Outcome(tooHard)) }
    }
    if (notHere) ProblemRow(Wording.NOT_HERE, Wording.notHereExplained(noun), onNotHere)
    if (notHere && canMove) ProblemRow(Wording.MOVED, Wording.MOVED_EXPLAINED, onMoved)
    outcomes.filter { it.id != ChoiceOutcome.TOO_HARD_ID }.forEach { outcome ->
        ProblemRow(outcome.label, outcome.description) { onChoose(ChoiceAction.Outcome(outcome)) }
    }
}

@Composable
private fun ProblemRow(label: String, explanation: String?, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            explanation?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** How often the moved map refreshes its fix while open. */
private const val FIX_REFRESH_MS = 3_000L

/** "It's moved" (design/not-here-moved.md §3.2): tap the map, or "I'm standing at it" with a fix
 * within [MovedSpot.GOOD_FIX_M]. Only the picked spot is saved. */
@Composable
internal fun MovedPicker(
    mapped: LatLon,
    picked: LatLon?,
    fromHere: Boolean,
    noun: Noun,
    styleUrl: String,
    onPick: (LatLon, Boolean) -> Unit,
    onSave: (LatLon) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(Locator.permitted(context)) }
    // The latest fix within [MovedSpot.GOOD_FIX_M]; "I'm standing at it" waits for one, as on iOS.
    var goodFix by remember { mutableStateOf<LatLon?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = Locator.permitted(context)
    }
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Locator.PERMISSIONS) }
    LaunchedEffect(permitted) {
        while (permitted && isActive) {
            val fix = Locator.locate(context) as? LocateResult.Found
            if (fix != null && (fix.accuracyM ?: Float.MAX_VALUE) <= MovedSpot.GOOD_FIX_M) goodFix = fix.position
            delay(FIX_REFRESH_MS)
        }
    }
    Text(Wording.movedTitle(noun), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    // The accessible path comes first: tapping the map isn't usable with TalkBack.
    BigButton(Wording.STANDING_AT_IT, enabled = goodFix != null) {
        goodFix?.let { onPick(it, true) }
    }
    if (goodFix == null) {
        Text(Wording.WAITING_FOR_FIX, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(Wording.MOVED_HELP)
    MovedSpotMap(
        styleUrl, mapped, picked, Wording.ON_THE_MAP, interactive = true,
        onPick = { spot -> onPick(spot, false) },
        modifier = Modifier.fillMaxWidth().height(380.dp),
    )
    val meters = picked?.let { Places.meters(mapped, it) }
    if (meters != null) {
        Text(Wording.movedDistance(meters), style = MaterialTheme.typography.titleMedium)
        when (MovedSpot.check(meters)) {
            MovedSpot.Check.TOO_CLOSE -> Banner(Wording.MOVED_TOO_CLOSE)
            MovedSpot.Check.FAR -> Banner(Wording.movedFar(meters, noun))
            MovedSpot.Check.TOO_FAR -> Banner(Wording.movedTooFar(noun))
            MovedSpot.Check.OK -> Unit
        }
    }
    Text(
        if (picked != null && fromHere) Wording.spotIsHere(noun) else Wording.SPOT_ONLY,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BigButton(Wording.SAVE_THIS_SPOT, prominent = true, enabled = picked != null && meters != null && MovedSpot.canSave(meters)) {
        picked?.let(onSave)
    }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
}

/** "Check your report": always shown before a not-here or moved report is sent. */
@Composable
internal fun ReportReview(
    report: ChoiceAction,
    mapped: LatLon?,
    noun: Noun,
    styleUrl: String,
    previewing: Boolean,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val moved = report as? ChoiceAction.Moved
    val spot = moved?.let { LatLon(it.location.latitude, it.location.longitude) }
    Text(Wording.CHECK_REPORT, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(Wording.REPORTING, fontWeight = FontWeight.SemiBold)
    if (spot != null && mapped != null) {
        Text(Wording.reportMoved(Places.meters(mapped, spot), noun), style = MaterialTheme.typography.titleMedium)
        MovedSpotMap(
            styleUrl, mapped, spot, Wording.ON_THE_MAP, interactive = false, onPick = {},
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
        Text(Wording.REPORT_MOVED_NOTE)
    } else {
        Text(Wording.reportNotHere(noun), style = MaterialTheme.typography.titleMedium)
        Text(Wording.REPORT_NOT_HERE_NOTE)
    }
    BigButton(if (previewing) Wording.DONE_PREVIEW else Wording.DONE, prominent = true, onClick = onDone)
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back") }
}

/** A not-here or moved report went through. */
@Composable
internal fun ReportSaved(moved: Boolean) {
    Text(
        "✓ " + if (moved) Wording.REPORTED_MOVED else Wording.REPORTED_NOT_HERE,
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
    )
}

/** "Next nearest …" ([nextLabel], or the last-one note when null) and the way back to the list. */
@Composable
internal fun NextButtons(noun: Noun, nextLabel: String?, prominent: Boolean, onNext: () -> Unit, onBack: () -> Unit) {
    if (nextLabel != null) {
        BigButton(nextLabel, prominent, onClick = onNext)
    } else {
        Text(Wording.lastOne(noun), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    BackButton(noun, onBack)
}

@Composable
private fun Preview(
    work: TaskWork.Choice,
    noun: Noun,
    session: AppSession,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    back: @Composable () -> Unit,
) {
    Text("Sign in to answer", style = MaterialTheme.typography.titleMedium)
    val count = work.questions.size
    Text("You'll be asked ${if (count == 1) "one question" else "$count questions"} about this ${noun.one}:")
    work.questions.forEach { Text("• ${it.prompt}") }
    if (session.config.writesAllowed) {
        BigButton(if (signedIn) "Sign in again to answer" else "Sign in with OpenStreetMap", prominent = true, onClick = onSignIn)
        Text(Wording.NEEDS_ACCOUNT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    back()
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Banner(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun BackButton(noun: Noun, onBack: () -> Unit) {
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(Wording.backToAll(noun)) }
}

@Composable
private fun BigButton(title: String, prominent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    if (prominent) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(title, style = MaterialTheme.typography.titleMedium) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(title, style = MaterialTheme.typography.titleMedium) }
    }
}

/** A full-width answer with a visible edge, so it holds up in glare. */
@Composable
private fun AnswerButton(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceVariant,
        border = BorderStroke(if (selected) 2.dp else 1.5.dp, if (selected) colors.primary else colors.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { this.selected = selected }.clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                detail?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.onSurfaceVariant) }
            }
            if (selected) {
                Spacer(Modifier.padding(start = 8.dp))
                Text("✓", style = MaterialTheme.typography.titleLarge, color = colors.primary)
            }
        }
    }
}
