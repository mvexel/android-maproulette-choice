package lol.osm.streettally.ui

import java.net.URI
import java.util.Collections
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lol.osm.streettally.AppConfig
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.task.woodbine
import org.maproulette.sdk.HttpMethod
import org.maproulette.sdk.HttpRequest
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.Transport

/** One Woodbine entrance in [WoodbineBackend]: [live] payloads take guest answers; others don't.
 * [status] is the task status (0 open, 1 fixed). */
internal data class Door(val id: Long, val name: String, val position: LatLon, val live: Boolean = true, val status: Int = 0)

/**
 * The Woodbine campaign (challenge 3) on dev, served without a network: the campaign, its doors, the
 * live check, guest registration, its token and status, guest answers, the picker's campaign search and the
 * bounds search that flags held places (`includePending`). Anything else is a 404. Records every guest
 * answer in [pending] (task id to request body; one per task, as the server keeps them) and every
 * attempt in [answerPosts]. With [dropNextAnswer], the next answer is stored but its response is lost.
 */
internal class WoodbineBackend(val doors: List<Door> = DOORS) : Transport {
    val pending: MutableMap<Long, String> = Collections.synchronizedMap(linkedMapOf())
    val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val answerPosts: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    @Volatile var dropNextAnswer = false

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val path = URI(request.url).path
        paths += "${request.method} $path"
        val api = path.removePrefix("/api/v2/")
        return when {
            path == "/oauth/mobile/guest" && request.method == HttpMethod.POST ->
                json(201, """{"guestId":"guest-1","guestSecret":"secret-secret-secret-1"}""")
            path == "/oauth/mobile/token" && request.method == HttpMethod.POST ->
                json(200, """{"token_type":"Bearer","access_token":"guest-token-guest-token-1","expires_in":3600,"scope":"guest"}""")
            api == "mobile-guest/me" -> json(200,
                """{"guestId":"guest-1","state":"active","email":"none","pending":${pending.size},"published":0,""" +
                    """"expiresAt":"2026-11-10T00:00:00Z"}""")
            api == "challenge/$CAMPAIGN" -> json(200,
                """{"id":$CAMPAIGN,"parent":1,"name":"$NAME","description":"Check the doors at the Woodbine entrances.",""" +
                    """"enabled":true,"isArchived":false,"cooperativeType":3}""")
            api == "challenges/extendedFind" -> json(200,
                """[{"id":$CAMPAIGN,"parent":1,"name":"$NAME","description":"Check the doors at the Woodbine entrances.",""" +
                    """"enabled":true,"isArchived":false,"cooperativeType":3,"tags":["mobile-survey-v1"]}]""")
            api.startsWith("tasks/box/") -> {
                val rows = if (request.url.contains("page=0")) doors.filter { it.status == 0 } else emptyList()
                json(200, rows.joinToString(",", """{"total":${rows.size},"tasks":[""", "]}") { summary(it) })
            }
            api == "challenge/$CAMPAIGN/tasks" -> {
                val limit = Regex("[?&]limit=([0-9]+)").find(request.url)?.groupValues?.get(1)?.toInt() ?: doors.size
                json(200, if (request.url.contains("page=0")) doors.take(limit).joinToString(",", "[", "]") { task(it) } else "[]")
            }
            else -> door(api)?.let { (door, rest) ->
                when {
                    rest == "" -> json(200, task(door))
                    rest == "/choice/check" -> json(200, """{"eligible":true,"deleteAllowed":false}""")
                    rest == "/choice/pending" && request.method == HttpMethod.POST -> if (!door.live) {
                        json(422, """{"error":"unsupported_task"}""")
                    } else {
                        answerPosts += door.id
                        pending[door.id] = request.body.orEmpty()
                        if (dropNextAnswer) {
                            dropNextAnswer = false
                            throw java.io.IOException("connection reset")
                        }
                        json(200, """{"taskId":${door.id},"challengeId":$CAMPAIGN,"state":"pending","answeredAt":"2026-10-11T12:00:00Z"}""")
                    }
                    else -> null
                }
            } ?: json(404, """{"error":"not_found"}""")
        }
    }

    private fun door(api: String): Pair<Door, String>? {
        if (!api.startsWith("task/")) return null
        val id = api.removePrefix("task/").substringBefore('/').toLongOrNull() ?: return null
        val door = doors.firstOrNull { it.id == id } ?: return null
        return door to api.removePrefix("task/$id")
    }

    private fun task(door: Door): String {
        val work = if (door.live) woodbine() else JsonObject(woodbine() - "liveMissingQuestions")
        val point = """{"type":"Point","coordinates":[${door.position.lon},${door.position.lat}]}"""
        return """{"id":${door.id},"parent":$CAMPAIGN,"name":"${door.id}","status":${door.status},""" +
            """"geometries":{"type":"FeatureCollection","features":[{"type":"Feature","geometry":$point,""" +
            """"properties":{"name":${JsonPrimitive(door.name)},"entrance":"main"}}]},""" +
            """"location":$point,"cooperativeWork":$work}"""
    }

    /** A bounds-search row; [pending] says whether a guest's saved answer holds it. */
    private fun summary(door: Door): String =
        """{"id":${door.id},"parentId":$CAMPAIGN,"title":"${door.id}","status":${door.status},""" +
            """"point":{"lat":${door.position.lat},"lng":${door.position.lon}},"pending":${door.id in pending}}"""

    private fun json(status: Int, body: String) = HttpResponse(status, body = body)

    companion object {
        const val CAMPAIGN = 3L
        const val NAME = "Woodbine entrance doors"
        private val ORIGIN = LatLon(43.68420, -79.31780)
        private fun north(meters: Double) = LatLon(ORIGIN.lat + meters / 111_320.0, ORIGIN.lon)

        /** Three doors that take guest answers, in id order, and one that needs an account. */
        val DOORS = listOf(
            Door(101, "Main door", ORIGIN),
            Door(102, "Food hall door", north(60.0)),
            Door(103, "East door", north(120.0)),
            Door(104, "Loading dock door", north(180.0), live = false),
        )

        /** Dev: answers are kept for guests. */
        val CONFIG = AppConfig(
            baseUrl = "https://mr-api.osm.lol",
            oauthClientId = "maproulette-ios-choice",
            callbackScheme = "lol.osm.maproulette.choice",
            allowLiveWrites = false,
            mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
            campaignLinkHost = "streettally.osm.lol",
            guests = true,
        )
    }
}
