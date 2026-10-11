package lol.osm.streettally.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.CheckedLog
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.LocatedTask
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.Places
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.task.ChoiceAction
import lol.osm.streettally.task.ChoiceForm
import lol.osm.streettally.task.ClientTaskOps
import lol.osm.streettally.task.TaskScreen
import lol.osm.streettally.task.TaskText
import lol.osm.streettally.task.TaskWorkController
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.ChoiceQuestion
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
) {
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    var taskId by remember { mutableStateOf(firstTaskId) }
    val visited = remember { mutableSetOf(firstTaskId) }
    var controller by remember { mutableStateOf(newController(session, scope, noun, campaignId)) }
    var switchNotice by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    // The question on screen; questions.size is the review step.
    var step by remember { mutableIntStateOf(0) }
    var notSure by remember { mutableStateOf(emptySet<String>()) }
    var counted by remember { mutableStateOf(false) }
    var showProblems by remember { mutableStateOf(false) }

    // Guest registration changes generation. Bind only after restore/registration finishes.
    LaunchedEffect(state.restored, state.generation) {
        if (!state.restored) return@LaunchedEffect
        session.ensureGuest()
        if (session.state.value.generation != state.generation) return@LaunchedEffect
        val old = controller
        val interrupted = old.busy || old.state.value is TaskScreen.CheckAgain
        old.cancel()
        confirmation = null
        controller = newController(session, scope, noun, campaignId)
        switchNotice = if (interrupted) {
            "Your sign-in changed while your answers were being saved, so we can't tell whether they were."
        } else null
        step = 0
        notSure = emptySet()
        val recovery = runCatching { session.pendingSaves.read() }.getOrNull()
        if (recovery != null && recovery.binding == session.writer()?.guestBinding && recovery.campaignId == campaignId) {
            taskId = recovery.taskId
            visited += taskId
        }
        controller.load(TaskId(taskId))
    }
    val c = controller
    val screen by c.state.collectAsState()
    // Count a place this person saved (not skipped, not someone else's result) once.
    LaunchedEffect(c, screen) {
        val done = screen as? TaskScreen.Done
        if (done != null && !done.byOther && !counted) {
            counted = true
            checked.record(campaignId, done.task.id.value)
        }
    }

    val here: LatLon? = loadedTask(screen)?.let { Places.position(it) }
        ?: candidates.firstOrNull { it.id == taskId }?.position
    val next = Places.nearest(here, candidates, visited + checked.done(campaignId))

    fun open(place: LocatedTask) {
        if (c.busy) return
        switchNotice = null
        taskId = place.id
        visited += place.id
        counted = false
        step = 0
        notSure = emptySet()
        controller = newController(session, scope, noun, campaignId)
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
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            loadedTask(screen)?.let { task ->
                Header(task, noun, if (task.id.value == firstTaskId) Places.fromYouText(origin, Places.position(task)) else null)
                task.lockedBy?.let { if (it != state.account?.userId) Banner("Someone else may be checking this ${noun.one} right now. You can still answer.") }
            }
            if (screen !is TaskScreen.Loading) switchNotice?.let { Banner(it) }

            val nextButtons: @Composable (Boolean) -> Unit = { prominent ->
                if (next != null) {
                    BigButton(Wording.next(noun, Places.directionText(here, next.position)), prominent) { open(next) }
                } else {
                    Text(Wording.lastOne(noun), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BackButton(noun, onBack)
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
                is TaskScreen.Preview -> Preview(s.work, noun, session, state.account != null,
                    onGuestRetry = { scope.launch { session.ensureGuest() } }, onSignIn = onSignIn) { BackButton(noun, onBack) }
                is TaskScreen.CheckFailed -> {
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
                    if (!c.canAnswer) {
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
                                    notSure = if (optionId == null) notSure + id else notSure - id
                                    step += 1
                                },
                                onPrevious = { step -= 1 },
                            )
                        } else {
                            Review(c, s.task, s.form, noun, onEdit = { step = it })
                        }
                    }
                    Problems(c, s.task, s.form, noun, showProblems, { showProblems = it }) { action ->
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
                is TaskScreen.Done -> {
                    if (s.byOther) {
                        Text(Wording.checkedByOtherFirst(noun), style = MaterialTheme.typography.titleMedium)
                    } else {
                        // Counted once the effect above has recorded it; reading [counted] recomposes then.
                        val today = checked.today(campaignId) + if (counted) 0 else 1
                        Text(
                            "✓ " + Wording.saved(today, noun),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (s.savedForLater) Text(Wording.SAVED_FOR_LATER)
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
                    Text(if (c.isGuest) "We haven't confirmed these answers were saved."
                        else "The connection dropped, so we're not sure your answers were saved. Tap Check again to find out. They weren't sent twice.")
                    Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BigButton("Check again", prominent = true) { c.recheck() }
                    if (s.canResend) {
                        BigButton("Send the same answers again") {
                            confirmation = Confirmation(
                                "Send the same answers again?",
                                if (c.isGuest) "This replaces any earlier pending save for this ${noun.one} with the same answers. It does not publish to OpenStreetMap."
                                else "This finishes your earlier try. Your answers are never saved twice.",
                                "Send again", c,
                            ) { it.resendSame() }
                        }
                    }
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
private fun newController(session: AppSession, scope: CoroutineScope, noun: Noun, campaignId: Long): TaskWorkController =
    TaskWorkController(ClientTaskOps(session.client()), session.writer(), scope, noun = noun,
        pendingSaves = session.pendingSaves, campaignId = campaignId)

private fun loadedTask(screen: TaskScreen): Task? = when (screen) {
    TaskScreen.Loading, is TaskScreen.LoadFailed -> null
    is TaskScreen.NotAvailable -> screen.task
    is TaskScreen.Preview -> screen.task
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
    is TaskScreen.ElementInUse -> screen.task
}

@Composable
private fun Header(task: Task, noun: Noun, fromYou: String?) {
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

@Composable
private fun Review(c: TaskWorkController, task: Task, form: ChoiceForm, noun: Noun, onEdit: (Int) -> Unit) {
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
        BigButton(Wording.DONE, prominent = true, enabled = c.offers(task, form, action)) { c.perform(action) }
    }
}

/** "Something's wrong here": the campaign's outcomes, "I can't check this", and Skip. */
@Composable
private fun Problems(
    c: TaskWorkController,
    task: Task,
    form: ChoiceForm,
    noun: Noun,
    open: Boolean,
    setOpen: (Boolean) -> Unit,
    choose: (ChoiceAction) -> Unit,
) {
    val outcomes = form.outcomes.filter { c.offers(task, form, ChoiceAction.Outcome(it)) }
    val canSkip = c.offers(task, form, ChoiceAction.Skip)
    if (outcomes.isEmpty() && !canSkip) return
    HorizontalDivider()
    TextButton(onClick = { setOpen(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(Wording.SOMETHING_WRONG)
    }
    if (!open) return
    fun pick(action: ChoiceAction) {
        setOpen(false)
        choose(action)
    }
    AlertDialog(
        onDismissRequest = { setOpen(false) },
        title = { Text(Wording.SOMETHING_WRONG) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Wording.problemsMessage(noun))
                outcomes.filter { it.id != ChoiceOutcome.TOO_HARD_ID }.forEach { outcome ->
                    OutlinedButton(onClick = { pick(ChoiceAction.Outcome(outcome)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(outcome.label)
                    }
                }
                outcomes.firstOrNull { it.id == ChoiceOutcome.TOO_HARD_ID }?.let { tooHard ->
                    OutlinedButton(onClick = { pick(ChoiceAction.Outcome(tooHard)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(Wording.cantCheck(noun))
                    }
                }
                if (canSkip) {
                    OutlinedButton(onClick = { pick(ChoiceAction.Skip) }, modifier = Modifier.fillMaxWidth()) {
                        Text(Wording.skip(noun))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { setOpen(false) }) { Text("Cancel") } },
    )
}

@Composable
private fun Preview(
    work: TaskWork.Choice,
    noun: Noun,
    session: AppSession,
    signedIn: Boolean,
    onGuestRetry: () -> Unit,
    onSignIn: () -> Unit,
    back: @Composable () -> Unit,
) {
    val state by session.state.collectAsState()
    if (session.config.guestWritesAllowed && !signedIn) {
        Text(Wording.guestStart(noun))
        state.message?.let { Banner(it) }
        BigButton("Try again", prominent = true, enabled = !state.busy, onClick = onGuestRetry)
        back()
        return
    }
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
