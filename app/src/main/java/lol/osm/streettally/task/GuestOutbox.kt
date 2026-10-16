package lol.osm.streettally.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.ChoiceSubmission
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.MovedLocation
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskResolution
import org.maproulette.sdk.WriteProblem

/**
 * A guest's answers that didn't reach the server (no connection, or the reply was lost), kept on the
 * phone and sent again later with the same request. The server keeps one answer per task, so a resend
 * of one it did store replaces it rather than doubling it. Sent when the connection comes back, when
 * the app opens, and after the next answer that does get through (design: BACKLOG O1).
 */
class GuestOutbox(private val store: Store) {
    /** Where the queue lives: one string (prefs in the app, memory in tests). */
    interface Store {
        fun read(): String?
        fun write(value: String?)
    }

    data class Entry(val taskId: Long, val campaignId: Long, val submission: ChoiceSubmission)

    private val mutableEntries = MutableStateFlow(decode(store.read()))
    val entries: StateFlow<List<Entry>> = mutableEntries
    private val sending = Mutex()

    /** Queues [entry], replacing an earlier one for the same task (the server would replace it too). */
    @Synchronized
    fun add(entry: Entry) = save(mutableEntries.value.filter { it.taskId != entry.taskId } + entry)

    /** Removes [entry] only: a newer answer for the same place, queued meanwhile, stays. */
    @Synchronized
    private fun remove(entry: Entry) = save(mutableEntries.value.filter { it != entry })


    /** Sends what's queued, oldest first, through [ops]. Stops at the first answer that still can't
     * get through, keeping it and the rest. An answer the server refuses for good (the place was
     * checked meanwhile, the campaign closed) is dropped: it can never be saved. Returns how many
     * were saved. */
    suspend fun send(ops: TaskOps): Int = sending.withLock {
        var saved = 0
        for (entry in mutableEntries.value) {
            try {
                val task = ops.getTask(TaskId(entry.taskId))
                ops.submitPendingChoice(task, entry.submission)
                saved += 1
                remove(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (retryLater(e)) break
                remove(entry)
            }
        }
        saved
    }

    private fun save(entries: List<Entry>) {
        mutableEntries.value = entries
        store.write(if (entries.isEmpty()) null else encode(entries))
    }

    companion object {
        /** Worth trying again later: the connection, an unknown outcome, the server or its rate limit
         * (but not the per-phone answer limit), or a token to renew. Anything else won't change. */
        fun retryLater(error: Exception): Boolean {
            if (error !is MapRouletteException) return false
            if (error.problem == WriteProblem.OutcomeUnknown) return true
            return when (error.kind) {
                ErrorKind.NETWORK, ErrorKind.SERVER, ErrorKind.AUTHENTICATION -> true
                ErrorKind.RATE_LIMIT -> error.reason != "pending_limit"
                else -> false
            }
        }

        internal fun encode(entries: List<Entry>): String = buildJsonArray {
            for (e in entries) add(buildJsonObject {
                put("task", e.taskId)
                put("campaign", e.campaignId)
                when (val s = e.submission) {
                    is ChoiceSubmission.Answers -> put("answers", JsonObject(s.byQuestion.mapValues { JsonPrimitive(it.value) }))
                    is ChoiceSubmission.Outcome -> put("outcome", buildJsonObject {
                        put("id", s.outcome.id)
                        put("label", s.outcome.label)
                        s.outcome.description?.let { put("description", it) }
                        put("resolution", s.outcome.resolution.name)
                        put("deletes", s.outcome.deletesElement)
                    })
                    is ChoiceSubmission.Moved -> put("moved", buildJsonObject {
                        put("lat", s.location.latitude)
                        put("lon", s.location.longitude)
                        put("source", s.location.source.name)
                    })
                }
            })
        }.toString()

        /** Unreadable entries are dropped, never the whole queue. */
        internal fun decode(text: String?): List<Entry> {
            val array = text?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return emptyList()
            return array.mapNotNull { element -> runCatching { entry(element.jsonObject) }.getOrNull() }
        }

        private fun entry(o: JsonObject): Entry {
            val submission = when {
                "answers" in o -> ChoiceSubmission.Answers(o.getValue("answers").jsonObject.mapValues { it.value.jsonPrimitive.content })
                "outcome" in o -> o.getValue("outcome").jsonObject.let { c ->
                    ChoiceSubmission.Outcome(ChoiceOutcome(
                        id = c.getValue("id").jsonPrimitive.content,
                        label = c.getValue("label").jsonPrimitive.content,
                        description = c["description"]?.jsonPrimitive?.contentOrNull,
                        resolution = TaskResolution.valueOf(c.getValue("resolution").jsonPrimitive.content),
                        deletesElement = c.getValue("deletes").jsonPrimitive.boolean,
                    ))
                }
                else -> o.getValue("moved").jsonObject.let { m ->
                    ChoiceSubmission.Moved(MovedLocation(
                        m.getValue("lat").jsonPrimitive.double, m.getValue("lon").jsonPrimitive.double,
                        MovedLocation.Source.valueOf(m.getValue("source").jsonPrimitive.content),
                    ))
                }
            }
            return Entry(o.getValue("task").jsonPrimitive.long, o.getValue("campaign").jsonPrimitive.long, submission)
        }
    }
}
