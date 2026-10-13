package lol.osm.streettally.campaign

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.maproulette.sdk.OsmType
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.templateProperties
import org.maproulette.sdk.work

/** Footprints, not dots (design/which-one-sprint.md §3 E): a building draws its outline and a
 * crossing a bar across its street. The tasks carry only a point (a building's centroid, a
 * crossing's node), so the shapes come from the map's own vector tiles. As iOS `Footprint`. */
object Footprint {
    enum class Kind { POINT, BUILDING, CROSSING }

    /** What the close-up map draws for a campaign's places, from the task's identity tags. */
    fun kind(task: Task): Kind {
        val work = task.work() as? TaskWork.Choice ?: return Kind.POINT
        val properties = task.templateProperties()
        if ((work.match["highway"] ?: properties["highway"]) == "crossing") return Kind.CROSSING
        if (work.element.type != OsmType.NODE && (work.match["building"] ?: properties["building"]) != null) return Kind.BUILDING
        return Kind.POINT
    }

    /** A building outline counts when it holds the point, or else passes within this of it (a
     * centroid can fall outside an L-shaped building). */
    const val BUILDING_REACH_M = 8.0
    /** A crossing's street must pass within this of its node. */
    const val STREET_REACH_M = 6.0
    /** The drawn bar's length: about a two-lane street. */
    const val BAR_M = 12.0

    /** The outline for [point]: the ring holding it, else the nearest within [BUILDING_REACH_M]. */
    fun outline(point: LatLon, rings: List<List<LatLon>>): List<LatLon>? {
        rings.firstOrNull { contains(it, point) }?.let { return it }
        return rings
            .mapNotNull { ring -> nearest(ring, point)?.let { ring to it.first } }
            .filter { it.second <= BUILDING_REACH_M }
            .minByOrNull { it.second }?.first
    }

    /** The bar for a crossing at [point]: across the nearest street line within [STREET_REACH_M]. */
    fun bar(point: LatLon, streets: List<List<LatLon>>): List<LatLon>? {
        val street = streets.mapNotNull { nearest(it, point) }.filter { it.first <= STREET_REACH_M }
            .minByOrNull { it.first } ?: return null
        return bar(point, street.second)
    }

    /** A [length] metre line centred on [point], at right angles to a street running at [bearing]. */
    fun bar(point: LatLon, bearing: Double, length: Double = BAR_M): List<LatLon> {
        val side = Math.toRadians(bearing + 90)
        val half = length / 2
        val east = sin(side) * half
        val north = cos(side) * half
        return listOf(offset(point, -east, -north), offset(point, east, north))
    }

    /** Ray casting; a ring may or may not repeat its first point. */
    fun contains(ring: List<LatLon>, p: LatLon): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** The closest segment of [line] to [p]: its distance in metres and its bearing in degrees. */
    fun nearest(line: List<LatLon>, p: LatLon): Pair<Double, Double>? {
        if (line.size < 2) return null
        // A local plane in metres around p: fine at street scale.
        val k = cos(Math.toRadians(p.lat))
        fun x(c: LatLon) = (c.lon - p.lon) * k * M_PER_DEG
        fun y(c: LatLon) = (c.lat - p.lat) * M_PER_DEG
        var best: Pair<Double, Double>? = null
        for ((a, b) in line.zipWithNext()) {
            val ax = x(a)
            val ay = y(a)
            val dx = x(b) - ax
            val dy = y(b) - ay
            val length2 = dx * dx + dy * dy
            if (length2 <= 0) continue
            val t = (-(ax * dx + ay * dy) / length2).coerceIn(0.0, 1.0)
            val distance = hypot(ax + t * dx, ay + t * dy)
            if (distance < (best?.first ?: Double.POSITIVE_INFINITY)) {
                best = distance to (Math.toDegrees(atan2(dx, dy)) + 360) % 360
            }
        }
        return best
    }

    private const val M_PER_DEG = 111_320.0

    private fun offset(c: LatLon, east: Double, north: Double) =
        LatLon(c.lat + north / M_PER_DEG, c.lon + east / (M_PER_DEG * cos(Math.toRadians(c.lat))))
}
