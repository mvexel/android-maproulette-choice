package lol.osm.streettally.demo

import java.io.IOException
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maproulette.sdk.HttpMethod
import org.maproulette.sdk.HttpRequest
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.Transport

/**
 * The demo build's backend (the `demo` flavor): a Woodbine micro campaign served in-process from
 * its survey file (backend/woodbine-micro in the project files), as on iOS `-demoTrees`,
 * `-demoCrossings` and `-demoBuildings`. Nothing goes over the network: every request is answered
 * here, guest answers are kept in memory ([pending]) and anything else is a 404.
 *
 * Campaign [campaignId] (its own per demo name, [IDS]); the file's features are tasks 1001 onwards
 * in file order, named after their OSM element (`node/<id>`, `way/<id>`). [surveys] maps a demo name (trees, crossings, buildings) to
 * its file; [use] switches campaign and starts afresh. In trees, 1090 and 1094 are checked and
 * another guest holds 1095 with a report, so the cluster around 1092 shows every neighbour dot.
 * [LARGE] is the trees survey copied side by side up to [places] tasks, for open-time checks at
 * thousands of places (L1, AE6b); [latencyMs] holds every answer back as a slow connection would.
 */
class DemoBackend(
    private val surveys: Map<String, String>,
    /** Each request and its status, for logcat in the demo build. */
    private val log: (String) -> Unit = {},
) : Transport {
    constructor(survey: String) : this(mapOf(TREES to survey))

    private class Tree(val id: Long, val element: String, val lat: Double, val lon: Double, val properties: JsonObject)

    /** [count]: the file's features copied side by side up to that many tasks, each its own node. */
    private class Campaign(survey: String, seeded: Boolean, count: Int? = null, checked: Int = 0) {
        val file = Json.parseToJsonElement(survey).jsonObject
        val challenge = file["challenge"]!!.jsonObject
        val trees = file["features"]!!.jsonObject["features"]!!.jsonArray.mapIndexed { i, f ->
            val coordinates = f.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
            val properties = f.jsonObject["properties"]!!.jsonObject
            Tree(
                FIRST_TASK + i,
                properties["@id"]!!.jsonPrimitive.content,
                coordinates[1].jsonPrimitive.double,
                coordinates[0].jsonPrimitive.double,
                JsonObject(properties - "@id"),
            )
        }.let { base ->
            if (count == null) base else List(count) { i ->
                val copy = i / base.size
                val tree = base[i % base.size]
                Tree(FIRST_TASK + i, "node/${SYNTHETIC_NODE + i}", tree.lat + copy / 8 * COPY_LAT, tree.lon + copy % 8 * COPY_LON, tree.properties)
            }
        }
        val status = trees.associate { it.id to 0 }.toMutableMap().apply {
            // The first [checked] places come back fixed: whole copies of the survey, so some clusters are all checked.
            trees.take(checked).forEach { put(it.id, 1) }
            if (seeded) {
                put(1090, 1)
                put(1094, 1)
            }
        }
        val othersHolds = if (seeded) mapOf(1095L to "report") else emptyMap()
    }

    /** The campaign being served, by name; parsed on the first request after a switch, off the main thread. */
    @Volatile private var name = surveys.keys.first()
    private var parsed: Pair<String, Campaign>? = null
    private val campaign: Campaign
        get() = synchronized(this) {
            parsed?.takeIf { it.first == name }?.second
                ?: Campaign(surveys.getValue(name), seeded = name == TREES, count = places.takeIf { name == LARGE }, checked = checked.takeIf { name == LARGE } ?: 0)
                    .also { parsed = name to it }
        }

    /** How many tasks [LARGE] has (the `places` extra); a change takes effect on its next [use]. */
    @Volatile var places: Int = LARGE_PLACES

    /** How many of [LARGE]'s places are already checked (the `checked` extra), as for [places]. */
    @Volatile var checked: Int = 0

    /** How long every request waits before its answer, as on a slow connection (the `latency` extra). */
    @Volatile var latencyMs: Long = 0

    /** Every request waits and never answers, as on one bar of signal (the `hang` extra). */
    @Volatile var hang: Boolean = false

    /** Task id to the request body of this guest's saved answer. */
    val pending: MutableMap<Long, String> = Collections.synchronizedMap(linkedMapOf())

    /** Saving an answer fails as if there were no connection (the `offline` extra); reads still work. */
    @Volatile var offline: Boolean = false

    /** Answers a local mapper has already put on the map, reported in the guest status (the `onmap` extra). */
    @Volatile var onMap: Int = 0

    /** Serves the [name]d campaign from now on, with no answers saved; false when there's none. */
    fun use(name: String): Boolean {
        if (name !in surveys) return false
        synchronized(this) {
            this.name = name
            parsed = null
        }
        pending.clear()
        return true
    }

    /** The served campaign's id: each demo campaign has its own, so its day count and tally are its own. */
    val campaignId: Long get() = IDS[name] ?: CAMPAIGN

    private val file get() = campaign.file
    private val challenge get() = campaign.challenge
    private val trees get() = campaign.trees
    private val status get() = campaign.status
    private val othersHolds get() = campaign.othersHolds

    /** Answered on a background thread, as a real transport would: parsing a survey file on the
     * main thread stalled the emulator into "isn't responding" (Tess). */
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.Default) {
        if (offline) {
            log("${request.method} ${request.url} -> no connection (offline extra)")
            throw IOException("demo: offline")
        }
        if (hang) awaitCancellation()
        if (latencyMs > 0) delay(latencyMs)
        answer(request).also { log("${request.method} ${request.url} -> ${it.status}") }
    }

    private fun answer(request: HttpRequest): HttpResponse {
        val uri = URI(request.url)
        val path = uri.path
        val query = uri.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val api = path.removePrefix("/api/v2/")
        // The app reopens the last campaign by id after a relaunch, when trees is served again.
        if (api.startsWith("challenge/")) {
            val id = api.removePrefix("challenge/").substringBefore('/').toLongOrNull()
            IDS.entries.firstOrNull { it.value == id && it.key in surveys && it.key != name }?.let { use(it.key) }
        }
        return when {
            path == "/oauth/mobile/guest" && request.method == HttpMethod.POST ->
                json(201, """{"guestId":"demo-guest","guestSecret":"demo-secret-demo-secret"}""")
            path == "/oauth/mobile/token" && request.method == HttpMethod.POST && !request.body.orEmpty().contains("authorization_code") ->
                json(200, """{"token_type":"Bearer","access_token":"demo-guest-token-demo-guest","expires_in":86400,"scope":"guest"}""")
            api == "mobile-guest/me" -> json(200,
                """{"guestId":"demo-guest","state":"active","email":"none","pending":${pending.size},"published":$onMap,""" +
                    """"expiresAt":"2099-01-01T00:00:00Z","byChallenge":{"$campaignId":{"pending":${pending.size},"onMap":$onMap}}}""")
            api == "mobile-guest" && request.method == HttpMethod.DELETE -> {
                pending.clear()
                HttpResponse(204, body = "")
            }
            api == "challenge/$campaignId" -> json(200, campaign().toString())
            api == "challenges/extendedFind" -> json(200, if (query["page"].orEmpty() in setOf("", "0")) "[${campaign(tagged = true)}]" else "[]")
            api == "challenge/$campaignId/tasks" -> json(200, page(trees, query).joinToString(",", "[", "]") { task(it).toString() })
            api.startsWith("tasks/box/") -> {
                val open = trees.filter { status[it.id] in BOX_STATUSES }
                json(200, page(open, query).joinToString(",", """{"total":${open.size},"tasks":[""", "]}") { summary(it) })
            }
            else -> tree(api)?.let { (tree, rest) ->
                when {
                    rest == "" -> json(200, task(tree).toString())
                    rest == "/choice/check" -> json(200, """{"eligible":true,"deleteAllowed":false}""")
                    rest == "/choice/pending" && request.method == HttpMethod.POST -> {
                        pending[tree.id] = request.body.orEmpty()
                        json(200, """{"taskId":${tree.id},"challengeId":$campaignId,"state":"pending","answeredAt":"2026-10-13T12:00:00Z"}""")
                    }
                    else -> null
                }
            } ?: json(404, """{"error":"not_found"}""")
        }
    }

    private fun <T> page(rows: List<T>, query: Map<String, String>): List<T> {
        val limit = query["limit"]?.toIntOrNull() ?: rows.size
        val page = query["page"]?.toIntOrNull() ?: 0
        return rows.drop(page * limit).take(limit)
    }

    private fun tree(api: String): Pair<Tree, String>? {
        if (!api.startsWith("task/")) return null
        val id = api.removePrefix("task/").substringBefore('/').toLongOrNull() ?: return null
        val tree = trees.firstOrNull { it.id == id } ?: return null
        return tree to api.removePrefix("task/$id")
    }

    private fun campaign(tagged: Boolean = false) = buildJsonObject {
        put("id", campaignId)
        put("parent", 1)
        put("name", challenge["name"]!!)
        put("description", challenge["description"]!!)
        put("enabled", true)
        put("isArchived", false)
        put("cooperativeType", 3)
        if (tagged) put("tags", JsonArray(listOf(JsonPrimitive("mobile-survey-v1"))))
    }

    private fun point(tree: Tree) = Json.parseToJsonElement("""{"type":"Point","coordinates":[${tree.lon},${tree.lat}]}""")

    private fun task(tree: Tree) = buildJsonObject {
        put("id", tree.id)
        put("parent", campaignId)
        put("name", tree.element)
        put("status", status[tree.id] ?: 0)
        put("geometries", Json.parseToJsonElement(
            """{"type":"FeatureCollection","features":[{"type":"Feature","id":"${tree.element}",""" +
                """"geometry":${point(tree)},"properties":${tree.properties}}]}""",
        ))
        put("location", point(tree))
        put("cooperativeWork", work(tree))
    }

    /** The task's choice payload: the survey's questions and outcomes for this feature. A survey that
     * matches by key only (`matchKeys`, buildings) gets each feature's own values, as the survey
     * builder writes them. */
    private fun work(tree: Tree) = JsonObject(
        mapOf(
            "meta" to Json.parseToJsonElement("""{"version":2,"type":3,"choiceVersion":1}"""),
            "element" to JsonPrimitive(tree.element),
            "match" to match(tree),
            "liveMissingQuestions" to JsonPrimitive(true),
            "questions" to file["questions"]!!,
            "outcomes" to file["outcomes"]!!,
        ),
    )

    private fun match(tree: Tree): JsonObject {
        val match = file["match"]!!.jsonObject
        val keys = (file["matchKeys"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        if (match.isNotEmpty() || keys.isEmpty()) return match
        return JsonObject(keys.mapNotNull { key -> tree.properties[key]?.let { key to it } }.toMap())
    }

    /** A bounds-search row; held by this guest's saved answer or report, or by another guest's. */
    private fun summary(tree: Tree): String {
        val mine = pending[tree.id]?.let { if (it.contains("\"outcome\"")) "report" else "answer" }
        val kind = mine ?: othersHolds[tree.id]
        return """{"id":${tree.id},"parentId":$campaignId,"title":"${tree.element}","status":${status[tree.id]},""" +
            """"point":{"lat":${tree.lat},"lng":${tree.lon}},"pending":${kind != null},""" +
            """"heldKind":${kind?.let { "\"$it\"" } ?: "null"}}"""
    }

    private fun json(status: Int, body: String) = HttpResponse(status, body = body)

    companion object {
        /** The trees campaign's id; crossings and buildings have their own ([IDS]). */
        const val CAMPAIGN = 3L
        val IDS = mapOf(TREES to CAMPAIGN, "crossings" to 4L, "buildings" to 5L, LARGE to 6L)
        const val FIRST_TASK = 1001L
        /** The demo build's backend origin. Never resolved: requests stop at [DemoBackend]. */
        const val ORIGIN = "https://demo.invalid"
        /** The bundled survey file, in the demo flavor's assets. */
        const val ASSET = "demo/woodbine-trees.json"
        const val TREES = "trees"
        /** The trees survey at [places] tasks. */
        const val LARGE = "large"
        /** Tess's AE6b campaign size. */
        const val LARGE_PLACES = 5_200
        /** [LARGE]'s copies: OSM ids no real node has, and about 670 m apart, eight to a row. */
        private const val SYNTHETIC_NODE = 90_000_000_000L
        private const val COPY_LAT = 0.006
        private const val COPY_LON = 0.008
        /** The bounds search's statuses (SDK TaskFilter default): to check, skipped, needs another look. */
        private val BOX_STATUSES = setOf(0, 3, 6)
        /** The demo campaigns, by the name the `demo` launch extra takes, and their bundled files. */
        val ASSETS = linkedMapOf(
            TREES to ASSET, "crossings" to "demo/woodbine-crossings.json", "buildings" to "demo/woodbine-buildings.json", LARGE to ASSET,
        )
    }
}
