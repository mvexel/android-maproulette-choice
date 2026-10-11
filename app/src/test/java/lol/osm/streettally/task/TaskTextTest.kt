package lol.osm.streettally.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.ChoiceOption
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.work

class TaskTextTest {
    private val work = task().work() as TaskWork.Choice
    private fun form(answers: Map<String, String>, deletion: Boolean = false) =
        ChoiceForm(work, outcomes(task(), deletion), answers)

    @Test
    fun statusWording() {
        assertEquals("Not an issue", TaskText.status(2))
        assertEquals("Unknown status (42)", TaskText.status(42))
    }

    @Test
    fun tagChangeShowsTheExactEdit() {
        assertEquals("backrest=yes", TaskText.tagChange(work.questions[0].options[0]))
        assertEquals("material=wood, remove note",
            TaskText.tagChange(ChoiceOption("x", "X", null, mapOf("material" to "wood"), listOf("note"))))
    }

    @Test
    fun answersSummaryListsExactTagChangesAndTheDevServer() {
        val text = TaskText.answersSummary(form(mapOf("backrest" to "no")), 42, 7, TaskText.DEV_OSM)
        assertTrue(text, text.contains("node/123"))
        assertTrue(text, text.contains("• backrest=no"))
        assertFalse(text, text.contains("material="))
        assertTrue("unanswered questions are listed as can't tell", text.contains("Can't tell") && text.contains("What is the seat mainly made of?"))
        assertTrue(text, text.contains("development OpenStreetMap server (master.apis.dev.openstreetmap.org), not the real map"))
        assertTrue(text, text.contains("task 42 as Fixed for user 7"))

        val both = TaskText.answersSummary(form(mapOf("backrest" to "yes", "material" to "metal")), 42, 7, null)
        assertTrue(both, both.contains("• backrest=yes\n• material=metal"))
        assertFalse(both, both.contains("Can't tell"))
        assertTrue(both, both.contains("test backend decides"))
    }

    @Test
    fun outcomeSummariesSayWhetherOsmIsEdited() {
        val goneOff = outcomes(task(), false).single { it.id == "gone" }
        val goneOn = outcomes(task(), true).single { it.id == "gone" }
        val off = TaskText.outcomeSummary(goneOff, work.element, 42, 7, TaskText.DEV_OSM)
        assertTrue(off, off.contains("“Not an issue”") && off.contains("does not edit OpenStreetMap"))
        val on = TaskText.outcomeSummary(goneOn, work.element, 42, 7, TaskText.DEV_OSM)
        assertTrue(on, on.startsWith("Delete node/123 from OpenStreetMap") && on.contains("development OpenStreetMap"))
        assertTrue(TaskText.outcomeExplanation(goneOn, work.element).contains("Deletes node/123"))
        val blocked = TaskText.outcomeSummary(goneOff, work.element, 42, 7, TaskText.DEV_OSM, notDeletable = true)
        assertTrue(blocked, blocked.contains("part of a way or relation") && blocked.contains("not deleted") &&
            blocked.contains("“Not an issue”") && blocked.contains("does not edit OpenStreetMap"))
        assertTrue(TaskText.outcomeExplanation(goneOff, work.element, notDeletable = true).contains("not deleted"))
    }

    @Test
    fun changesetLinksOnlyForAKnownServer() {
        assertEquals("https://master.apis.dev.openstreetmap.org/changeset/5", TaskText.changesetUrl(TaskText.DEV_OSM, 5))
        assertEquals(null, TaskText.changesetUrl(null, 5))
    }

    @Test
    fun nextTasksFollowTheListThenWrap() {
        assertEquals(listOf(3L, 4L, 1L), TaskText.nextTasks(listOf(1L, 2L, 3L, 4L), 2L))
        assertEquals(listOf(1L, 2L), TaskText.nextTasks(listOf(1L, 2L), 9L))
        assertEquals(emptyList<Long>(), TaskText.nextTasks(listOf(5L), 5L))
        assertEquals(emptyList<Long>(), TaskText.nextTasks(emptyList(), 5L))
    }

    @Test
    fun unavailableReasons() {
        assertTrue(TaskText.unavailableReason(task(cooperativeWork = null)).contains("multiple-choice"))
        assertTrue(TaskText.unavailableReason(task(bundleId = 3)).contains("bundle"))
        assertTrue(TaskText.unavailableReason(task(status = 1)).contains("nothing left"))
    }

    @Test
    fun instructionFallsBackToChallengeAndListsFormFields() {
        val text = TaskText.instruction(task(instruction = """Check {{#mrTaskId}} {{{select "Kind" name="k" values="a,b"}}}"""), CHALLENGE)
        assertTrue(text, text.startsWith("Check 42"))
        assertTrue(text, text.contains("Kind: a / b"))
        assertEquals("Add backrest", TaskText.instruction(task(), CHALLENGE))
    }

    @Test
    fun completedByComparesWithSignedInUser() {
        assertTrue(TaskText.completedBy(7, 7).startsWith("Completed by you"))
        assertTrue(TaskText.completedBy(8, 7).contains("not you"))
        assertTrue(TaskText.completedBy(null, 7).contains("did not report"))
    }
}
