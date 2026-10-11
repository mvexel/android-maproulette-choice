package lol.osm.streettally.task

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lol.osm.streettally.campaign.Noun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.ChoiceEligibility
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.ChoiceResult
import org.maproulette.sdk.ChoiceSubmission
import org.maproulette.sdk.TaskResolution
import org.maproulette.sdk.TaskStatus
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.work

/** The first pilot's payload, "Woodbine entrance doors" (design/woodbine-campaign.md), as the iOS
 * `-demoWoodbine` fixture serves it: two questions asked only where the tag is missing, and one
 * campaign outcome for open doorways. */
internal fun woodbine(entrance: String = "main"): JsonObject = Json.parseToJsonElement("""
    {"meta":{"version":2,"type":3,"choiceVersion":1},"element":"node/14178183367","match":{"entrance":"$entrance"},
     "liveMissingQuestions":true,
     "questions":[
      {"id":"door-type","prompt":"How does this door open?","description":"Look only at the door at this entrance.",
       "expect":{"door":null},
       "options":[{"id":"hinged","label":"Swings open","setTags":{"door":"hinged"}},
                  {"id":"sliding","label":"Slides sideways","setTags":{"door":"sliding"}},
                  {"id":"revolving","label":"Revolves","setTags":{"door":"revolving"}},
                  {"id":"folding","label":"Folds","setTags":{"door":"folding"}}]},
      {"id":"automatic-opening","prompt":"Does it open by itself?","description":"A door that's propped open doesn't count.",
       "expect":{"automatic_door":null},
       "options":[{"id":"no","label":"No, you push or pull it","setTags":{"automatic_door":"no"}},
                  {"id":"button","label":"Yes, with a button","setTags":{"automatic_door":"button"}},
                  {"id":"motion","label":"Yes, when you walk up","setTags":{"automatic_door":"motion"}},
                  {"id":"yes","label":"Yes, not sure how","setTags":{"automatic_door":"yes"}}]}],
     "outcomes":[{"id":"no-door","label":"There's no door","description":"It's an open doorway or a gate.","status":6}]}
""").jsonObject

@OptIn(ExperimentalCoroutinesApi::class)
class WoodbineTest {
    private fun TestScope.controller(ops: TaskOps): TaskWorkController =
        TaskWorkController(ops, Writer(7, canEditOsm = true), this, StandardTestDispatcher(testScheduler)).also {
            it.load(task().id)
            advanceUntilIdle()
        }

    private val TaskWorkController.answering get() = state.value as TaskScreen.Answering

    /** Answers every read with the Woodbine task; the check returns [questionIds]. */
    private class Ops(val questionIds: Set<String>? = null) : TaskOps {
        val submissions = mutableListOf<ChoiceSubmission>()
        override fun choiceOutcomes(task: org.maproulette.sdk.Task) = outcomes(task)
        override suspend fun getTask(id: org.maproulette.sdk.TaskId) =
            task(status = if (submissions.isEmpty()) 0 else 1, cooperativeWork = woodbine())
        override suspend fun getChallenge(task: org.maproulette.sdk.Task) = CHALLENGE
        override suspend fun checkChoice(id: org.maproulette.sdk.TaskId) =
            ChoiceEligibility(eligible = true, deleteAllowed = true, reason = null, questionIds = questionIds)
        override suspend fun submitChoice(task: org.maproulette.sdk.Task, submission: ChoiceSubmission): ChoiceResult {
            submissions += submission
            return ChoiceResult(TaskStatus(1), 99)
        }
        override suspend fun skipTask(id: org.maproulette.sdk.TaskId) = Unit
        override suspend fun submitPendingChoice(task: org.maproulette.sdk.Task, submission: ChoiceSubmission) =
            error("not a guest")
    }

    @Test
    fun theNounIsEntranceForAnyEntranceValue() {
        assertEquals("entrances", Noun.from(mapOf("entrance" to "main")).many)
        assertEquals("entrance", Noun.from(mapOf("entrance" to "yes")).one)
    }

    @Test
    fun bothQuestionsWithTheirOptionsAndDescriptions() {
        val work = task(cooperativeWork = woodbine("yes")).work() as TaskWork.Choice
        assertEquals(listOf("door-type", "automatic-opening"), work.questions.map { it.id })
        assertEquals(listOf("Swings open", "Slides sideways", "Revolves", "Folds"), work.questions[0].options.map { it.label })
        assertEquals("Look only at the door at this entrance.", work.questions[0].description)
        assertEquals(
            listOf("No, you push or pull it", "Yes, with a button", "Yes, when you walk up", "Yes, not sure how"),
            work.questions[1].options.map { it.label },
        )
        assertEquals("A door that's propped open doesn't count.", work.questions[1].description)
    }

    @Test
    fun noDoorIsOfferedAsTooHardWithoutEditingOsm() {
        val noDoor = outcomes(task(cooperativeWork = woodbine())).single { it.id == "no-door" }
        assertEquals("There's no door", noDoor.label)
        assertEquals("It's an open doorway or a gate.", noDoor.description)
        assertEquals(TaskResolution.TOO_HARD, noDoor.resolution)
        assertTrue(!noDoor.deletesElement && noDoor.id != ChoiceOutcome.TOO_HARD_ID)
    }

    @Test
    fun onlyTheMissingQuestionIsAsked() = runTest {
        val c = controller(Ops(questionIds = setOf("automatic-opening")))
        assertEquals(listOf("automatic-opening"), c.answering.form.work.questions.map { it.id })
        c.select("door-type", "hinged") // Not asked here: ignored
        c.select("automatic-opening", "button")
        assertEquals(mapOf("automatic-opening" to "button"), c.answering.form.answers)
    }

    @Test
    fun bothAnswersAreSent() = runTest {
        val ops = Ops()
        val c = controller(ops)
        assertEquals(2, c.answering.form.work.questions.size)
        c.perform(ChoiceAction.Answers(mapOf("door-type" to "hinged", "automatic-opening" to "button")))
        advanceUntilIdle()
        assertEquals(listOf<ChoiceSubmission>(ChoiceSubmission.Answers(mapOf("door-type" to "hinged", "automatic-opening" to "button"))), ops.submissions)
    }

    @Test
    fun noDoorIsSentAsTheCampaignOutcome() = runTest {
        val ops = Ops()
        val c = controller(ops)
        val noDoor = c.answering.form.outcomes.single { it.id == "no-door" }
        c.perform(ChoiceAction.Outcome(noDoor))
        advanceUntilIdle()
        assertEquals(listOf<ChoiceSubmission>(ChoiceSubmission.Outcome(noDoor)), ops.submissions)
    }
}
