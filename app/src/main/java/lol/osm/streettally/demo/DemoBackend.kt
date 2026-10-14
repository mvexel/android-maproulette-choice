package lol.osm.streettally.demo

import java.net.URI
import java.util.Collections
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
 * Campaign [CAMPAIGN]; the file's features are tasks 1001 onwards in file order, named after their
 * OSM element (`node/<id>`, `way/<id>`). [surveys] maps a demo name (trees, crossings, buildings) to
 * its file; [use] switches campaign and starts afresh. In trees, 1090 and 1094 are checked and
 * another guest holds 1095 with a report, so the cluster around 1092 shows every neighbour dot.
 */
class DemoBackend(private val surveys: Map<String, String>) : Transport {
    constructor(survey: String) : this(mapOf(TREES to survey))

    private class Tree(val id: Long, val element: String, val lat: Double, val lon: Double, val properties: JsonObject)

    private class Campaign(survey: String, seeded: Boolean) {
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
        }
        val status = trees.associate { it.id to 0 }.toMutableMap().apply {
            if (seeded) {
                put(1090, 1)
                put(1094, 1)
            }
        }
        val othersHolds = if (seeded) mapOf(1095L to "report") else emptyMap()
    }

    @Volatile private var campaign = Campaign(surveys.getValue(surveys.keys.first()), seeded = surveys.keys.first() == TREES)

    /** Task id to the request body of this guest's saved answer. */
    val pending: MutableMap<Long, String> = Collections.synchronizedMap(linkedMapOf())

    /** Serves the [name]d campaign from now on, with no answers saved; false when there's none. */
    fun use(name: String): Boolean {
        val survey = surveys[name] ?: return false
        campaign = Campaign(survey, seeded = name == TREES)
        pending.clear()
        return true
    }

    private val file get() = campaign.file
    private val challenge get() = campaign.challenge
    private val trees get() = campaign.trees
    private val status get() = campaign.status
    private val othersHolds get() = campaign.othersHolds

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val uri = URI(request.url)
        val path = uri.path
        val query = uri.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val api = path.removePrefix("/api/v2/")
        return when {
            path == "/oauth/mobile/guest" && request.method == HttpMethod.POST ->
                json(201, """{"guestId":"demo-guest","guestSecret":"demo-secret-demo-secret"}""")
            path == "/oauth/mobile/token" && request.method == HttpMethod.POST && !request.body.orEmpty().contains("authorization_code") ->
                json(200, """{"token_type":"Bearer","access_token":"demo-guest-token-demo-guest","expires_in":86400,"scope":"guest"}""")
            api == "mobile-guest/me" -> json(200,
                """{"guestId":"demo-guest","state":"active","email":"none","pending":${pending.size},"published":0,""" +
                    """"expiresAt":"2099-01-01T00:00:00Z"}""")
            api == "challenge/$CAMPAIGN" -> json(200, campaign().toString())
            api == "challenges/extendedFind" -> json(200, if (query["page"].orEmpty() in setOf("", "0")) "[${campaign(tagged = true)}]" else "[]")
            api == "challenge/$CAMPAIGN/tasks" -> json(200, page(trees, query).joinToString(",", "[", "]") { task(it).toString() })
            api.startsWith("tasks/box/") -> {
                val open = trees.filter { status[it.id] == 0 }
                json(200, page(open, query).joinToString(",", """{"total":${open.size},"tasks":[""", "]}") { summary(it) })
            }
            else -> tree(api)?.let { (tree, rest) ->
                when {
                    rest == "" -> json(200, task(tree).toString())
                    rest == "/choice/check" -> json(200, """{"eligible":true,"deleteAllowed":false}""")
                    rest == "/choice/pending" && request.method == HttpMethod.POST -> {
                        pending[tree.id] = request.body.orEmpty()
                        json(200, """{"taskId":${tree.id},"challengeId":$CAMPAIGN,"state":"pending","answeredAt":"2026-10-13T12:00:00Z"}""")
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
        put("id", CAMPAIGN)
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
        put("parent", CAMPAIGN)
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
        return """{"id":${tree.id},"parentId":$CAMPAIGN,"title":"${tree.element}","status":${status[tree.id]},""" +
            """"point":{"lat":${tree.lat},"lng":${tree.lon}},"pending":${kind != null},""" +
            """"heldKind":${kind?.let { "\"$it\"" } ?: "null"}}"""
    }

    private fun json(status: Int, body: String) = HttpResponse(status, body = body)

    companion object {
        const val CAMPAIGN = 3L
        const val FIRST_TASK = 1001L
        /** The demo build's backend origin. Never resolved: requests stop at [DemoBackend]. */
        const val ORIGIN = "https://demo.invalid"
        /** The bundled survey file, in the demo flavor's assets. */
        const val ASSET = "demo/woodbine-trees.json"
        const val TREES = "trees"
        /** The demo campaigns, by the name the `demo` launch extra takes, and their bundled files. */
        val ASSETS = linkedMapOf(TREES to ASSET, "crossings" to "demo/woodbine-crossings.json", "buildings" to "demo/woodbine-buildings.json")
    }
}
