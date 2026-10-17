package lol.osm.streettally.campaign

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.ProjectId
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskStatus

/** The last campaign opened, kept on the phone so it opens and can be answered in a dead spot
 * (BACKLOG O2). One campaign only: saving another replaces it. Answers made from it go through the
 * guest outbox; on the next load with signal the server's copy replaces this one. */
class CampaignCache(private val store: Store, private val clock: () -> Long = System::currentTimeMillis) {
    /** Where the copy lives: a file in the app, memory in tests. */
    interface Store {
        fun read(): String?
        fun write(value: String?)
    }

    /** [savedAt]: when the server last sent it, in epoch milliseconds. */
    data class Snapshot(val places: CampaignPlaces, val held: Held, val savedAt: Long)

    @Volatile
    private var cached: Snapshot? = null
    @Volatile
    private var loaded = false

    /** The copy of campaign [id], if that's the one kept. */
    fun get(id: Long): Snapshot? = current()?.takeIf { it.places.challenge.id.value == id }

    /** The campaign kept on this phone, if any: listed when the picker can't load (no signal). */
    fun keptCampaign(): Challenge? = current()?.places?.challenge

    /** The kept copy's place [id] with its campaign, for opening a place in a dead spot. */
    fun task(id: Long): Pair<Task, Challenge>? =
        current()?.let { s -> s.places.tasks.firstOrNull { it.id == id }?.let { it.task to s.places.challenge } }

    @Synchronized
    fun save(places: CampaignPlaces, held: Held) {
        val snapshot = Snapshot(places.copy(reports = emptyMap()), held, clock())
        cached = snapshot
        loaded = true
        runCatching { store.write(encode(snapshot)) }
    }

    /** Opening campaign [id]: a copy of any other one is dropped. */
    fun keepOnly(id: Long) {
        val kept = current() ?: return
        if (kept.places.challenge.id.value != id) clear()
    }

    /** Drops the copy held in memory, so the next use reads the file again, as after a restart (the
     * demo build's `cold` extra, for open-time checks). */
    @Synchronized
    fun forget() {
        cached = null
        loaded = false
    }

    @Synchronized
    fun clear() {
        cached = null
        loaded = true
        runCatching { store.write(null) }
    }

    @Synchronized
    private fun current(): Snapshot? {
        if (!loaded) {
            cached = runCatching { store.read()?.let(::decode) }.getOrNull()
            loaded = true
        }
        return cached
    }

    companion object {
        /** No connection (not a server's refusal): the case for showing the kept copy. */
        fun isOffline(error: Throwable): Boolean =
            error is IOException || (error is MapRouletteException && error.kind == ErrorKind.NETWORK)

        /** A file in the app's own storage. */
        fun fileStore(file: File): Store = object : Store {
            override fun read(): String? = if (file.exists()) file.readText() else null
            override fun write(value: String?) {
                if (value == null) {
                    file.delete()
                    return
                }
                // Written aside, then moved, so a kill mid-write never leaves half a campaign.
                val next = File(file.parentFile, file.name + ".new")
                next.writeText(value)
                if (!next.renameTo(file)) {
                    file.delete()
                    next.renameTo(file)
                }
            }
        }

        internal fun encode(s: Snapshot): String = buildJsonObject {
            put("savedAt", s.savedAt)
            put("truncated", s.places.truncated)
            put("challenge", challenge(s.places.challenge))
            put("tasks", buildJsonArray { s.places.tasks.forEach { add(task(it.task)) } })
            put("held", ids(s.held.ids))
            put("heldReports", ids(s.held.reports))
            s.held.offered?.let { put("offered", ids(it)) }
        }.toString()

        internal fun decode(text: String): Snapshot {
            val o = Json.parseToJsonElement(text).jsonObject
            val tasks = o.getValue("tasks").jsonArray.map { task(it.jsonObject) }
            val places = CampaignPlaces(
                challenge = challenge(o.getValue("challenge").jsonObject),
                // isOpen parses each choice payload: here, off the main thread, not by the tally (AE7).
                tasks = tasks.map { LocatedTask.of(it).also { place -> place.isOpen } },
                noun = Noun.from(tasks),
                truncated = o["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            val held = Held(ids(o["held"]), ids(o["heldReports"]), o["offered"]?.let(::ids))
            return Snapshot(places, held, o.getValue("savedAt").jsonPrimitive.long)
        }

        private fun ids(ids: Set<Long>) = JsonArray(ids.map { JsonPrimitive(it) })
        private fun ids(e: JsonElement?): Set<Long> = e?.jsonArray?.map { it.jsonPrimitive.long }?.toSet().orEmpty()

        private fun challenge(c: Challenge) = buildJsonObject {
            put("id", c.id.value)
            put("projectId", c.projectId.value)
            put("name", c.name)
            put("instruction", c.instruction)
            put("description", c.description)
            c.tags?.let { tags -> put("tags", JsonArray(tags.map { JsonPrimitive(it) })) }
            put("enabled", c.enabled)
            put("archived", c.archived)
            put("requiresLocal", c.requiresLocal)
            put("cooperativeType", c.cooperativeType)
        }

        private fun challenge(o: JsonObject) = Challenge(
            id = ChallengeId(o.getValue("id").jsonPrimitive.long),
            projectId = ProjectId(o.getValue("projectId").jsonPrimitive.long),
            name = o.getValue("name").jsonPrimitive.content,
            instruction = o.string("instruction"),
            description = o.string("description"),
            tags = (o["tags"] as? JsonArray)?.map { it.jsonPrimitive.content },
            enabled = o.bool("enabled"),
            archived = o.bool("archived"),
            requiresLocal = o.bool("requiresLocal"),
            cooperativeType = o.int("cooperativeType"),
        )

        private fun task(t: Task) = buildJsonObject {
            put("id", t.id.value)
            put("challengeId", t.challengeId.value)
            put("name", t.name)
            put("instruction", t.instruction)
            put("status", t.status?.code)
            put("geometry", t.geometry)
            put("location", t.location ?: JsonNull)
            put("cooperativeWork", t.cooperativeWork ?: JsonNull)
            put("lockedBy", t.lockedBy)
            put("completedBy", t.completedBy)
            put("mappedOn", t.mappedOn)
            put("reviewStatus", t.reviewStatus)
            put("bundleId", t.bundleId)
            put("changesetId", t.changesetId)
        }

        private fun task(o: JsonObject) = Task(
            id = TaskId(o.getValue("id").jsonPrimitive.long),
            challengeId = ChallengeId(o.getValue("challengeId").jsonPrimitive.long),
            name = o.getValue("name").jsonPrimitive.content,
            instruction = o.string("instruction"),
            status = o.int("status")?.let(::TaskStatus),
            geometry = o.getValue("geometry").jsonObject,
            location = o["location"] as? JsonObject,
            cooperativeWork = o["cooperativeWork"] as? JsonObject,
            lockedBy = o.long("lockedBy"),
            completedBy = o.long("completedBy"),
            mappedOn = o.string("mappedOn"),
            reviewStatus = o.int("reviewStatus"),
            bundleId = o.long("bundleId"),
            changesetId = o.long("changesetId"),
        )

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    }
}
