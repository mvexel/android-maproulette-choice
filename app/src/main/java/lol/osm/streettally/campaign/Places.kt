package lol.osm.streettally.campaign

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maproulette.sdk.MobileSupport
import org.maproulette.sdk.Task
import org.maproulette.sdk.mobileSupport

data class LatLon(val lat: Double, val lon: Double)

/** A challenge task with where it is. */
data class LocatedTask(val task: Task, val position: LatLon?) {
    val id: Long get() = task.id.value
    /** Answerable in place: a valid, open, unbundled choice task. Worked out once: it parses the
     * task's choice payload, and the tally asks for every place several times (AE6b, 5,000 places). */
    val isOpen: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) { task.mobileSupport() == MobileSupport.IN_PLACE }
    /** Already checked: fixed, not an issue or already fixed. */
    val isDone: Boolean get() = task.status?.code in DONE

    companion object {
        private val DONE = setOf(1, 2, 5)
        fun of(task: Task) = LocatedTask(task, Places.position(task))
    }
}

/** Positions, nearest-first order and distance wording. Location never leaves the phone. */
object Places {
    /** The task's `location` (a GeoJSON Point), else the first Point in its geometry. */
    fun position(task: Task): LatLon? {
        point(task.location)?.let { return it }
        val features = task.geometry["features"] as? JsonArray ?: return null
        for (feature in features) {
            point((feature as? JsonObject)?.get("geometry"))?.let { return it }
        }
        return null
    }

    /** `{"type": "Point", "coordinates": [lng, lat]}` with valid ranges. */
    fun point(value: JsonElement?): LatLon? {
        val o = value as? JsonObject ?: return null
        if ((o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "Point") return null
        val c = o["coordinates"] as? JsonArray ?: return null
        if (c.size < 2) return null
        val lon = number(c[0]) ?: return null
        val lat = number(c[1]) ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLon(lat, lon)
    }

    private fun number(value: JsonElement): Double? =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    /** Nearest first from [origin]; tasks without a position go last. Ties and the no-origin case
     * keep task id order. */
    fun sorted(tasks: List<LocatedTask>, origin: LatLon?): List<LocatedTask> {
        val byId = tasks.sortedBy { it.id }
        if (origin == null) return byId
        // Each distance once, not per comparison: 5,000 places re-sort on every fix (AE6b).
        return byId.map { t -> t to t.position?.let { meters(origin, it) } }
            .sortedWith(compareBy(nullsLast()) { it.second }).map { it.first }
    }

    /** Great-circle distance in meters (haversine). */
    fun meters(a: LatLon, b: LatLon): Double {
        val r = 6_371_008.8
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing in degrees, 0 = north, clockwise. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private val points = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")

    /** One of the 8 compass points for a bearing in degrees. */
    fun compass(degrees: Double): String {
        val normalized = ((degrees % 360) + 360) % 360
        return points[((normalized + 22.5) / 45).toInt() % 8]
    }

    /** "120 m" / "1.2 km", or "400 ft" / "0.6 mi" where [WhichOne.units] says feet, so one screen
     * never mixes the two. */
    fun distance(meters: Double, locale: Locale = Locale.getDefault()): String =
        if (WhichOne.units(locale) == WhichOne.Units.IMPERIAL) {
            val feet = meters * 3.28084
            if (feet < 1000) "${roundTo(feet, if (feet < 100) 10 else 50)} ft"
            else String.format(Locale.US, "%.1f mi", meters / 1609.344)
        } else {
            if (meters < 1000) "${roundTo(meters, 10)} m"
            else String.format(Locale.US, "%.1f km", meters / 1000)
        }

    private fun roundTo(value: Double, step: Int): Int = maxOf(step, (value / step).roundToInt() * step)

    fun distanceText(a: LatLon?, b: LatLon?, locale: Locale = Locale.getDefault()): String? =
        if (a == null || b == null) null else distance(meters(a, b), locale)

    /** The nearest open place to [origin] that is not in [excluding]; by id when [origin] is null. */
    fun nearest(origin: LatLon?, tasks: List<LocatedTask>, excluding: Set<Long>): LocatedTask? =
        sorted(tasks.filter { it.isOpen && it.id !in excluding }, origin).firstOrNull()

    /** "180 m northeast", for "Next nearest". */
    fun directionText(a: LatLon?, b: LatLon?, locale: Locale = Locale.getDefault()): String? =
        if (a == null || b == null) null else "${distance(meters(a, b), locale)} ${compass(bearing(a, b))}"

    /** "130 m northeast of you", for the place header. */
    fun fromYouText(you: LatLon?, place: LatLon?, locale: Locale = Locale.getDefault()): String? =
        directionText(you, place, locale)?.let { "$it of you" }

    /** "130 m · northeast", for list rows. */
    fun rowText(a: LatLon?, b: LatLon?, locale: Locale = Locale.getDefault()): String? =
        if (a == null || b == null) null else "${distance(meters(a, b), locale)} · ${compass(bearing(a, b))}"
}
