package lol.osm.streettally.campaign

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** "Which one?" (design/which-one-spec.md): the close-up task map's first frame, the neighbours
 * drawn around the target, and the live direction line under it. Location never leaves the phone. */
object WhichOne {
    /** Farther than this, the first frame shows only the target (spec A). */
    const val FAR_M = 150.0
    /** Zoom for the target-only frame, and the clamp for the you-and-target frame (spec A). */
    const val TARGET_ZOOM = 19.0
    const val MIN_ZOOM = 17.0
    const val MAX_ZOOM = 20.0
    /** Worse than this, the line says the location is rough (spec C rule 2). */
    const val ROUGH_M = 25f
    /** Closer than this, "You're at it" (spec C rule 3). */
    const val AT_IT_M = 5.0
    /** A side change needs the angle this far past the boundary, so a jittery compass doesn't flip
     * the line every second. */
    const val HYSTERESIS_DEG = 10.0

    /** The aerial photo (spec D, design/imagery-licence-check.md): USGS The National Map, mostly
     * NAIP, public domain and keyless. Note the y/x order. Cached to z16; the map overzooms past it. */
    const val IMAGERY_TILES = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}"
    const val IMAGERY_MAX_ZOOM = 16f
    const val IMAGERY_TILE_SIZE = 256

    enum class Units { IMPERIAL, METRIC }

    /** Feet in the US, Liberia and Myanmar; meters elsewhere, the UK included (Iris, parity notes).
     * Every distance line follows this, [Places.distance] too. */
    fun units(locale: Locale = Locale.getDefault()): Units = if (locale.country in FEET) Units.IMPERIAL else Units.METRIC

    private val FEET = setOf("US", "LR", "MM")

    /** Close-range distance (spec C): feet to 5 under 100 ft, else to 10, then miles to one decimal;
     * meters to 1 under 10 m, 5 under 100, else 10, then km to one decimal. */
    fun distance(meters: Double, units: Units): String = when (units) {
        Units.IMPERIAL -> {
            val feet = meters * 3.28084
            when {
                feet < 100 -> "${roundTo(feet, 5)} ft"
                feet < 1000 -> "${roundTo(feet, 10)} ft"
                else -> String.format(Locale.US, "%.1f mi", meters / 1609.344)
            }
        }
        Units.METRIC -> when {
            meters < 10 -> "${roundTo(meters, 1)} m"
            meters < 100 -> "${roundTo(meters, 5)} m"
            meters < 1000 -> "${roundTo(meters, 10)} m"
            else -> String.format(Locale.US, "%.1f km", meters / 1000)
        }
    }

    private fun roundTo(value: Double, step: Int): Int = maxOf(step, (value / step).roundToInt() * step)

    /** Where the target is relative to the way you face (spec C rule 4). */
    enum class Side(private val words: String) {
        AHEAD("ahead"),
        SLIGHTLY_LEFT("ahead, slightly left"),
        SLIGHTLY_RIGHT("ahead, slightly right"),
        LEFT("to your left"),
        RIGHT("to your right"),
        BEHIND_LEFT("behind you, to the left"),
        BEHIND_RIGHT("behind you, to the right"),
        BEHIND("behind you"),
        ;

        fun text(distance: String) = "$distance $words"
    }

    /** [relative]: the target's bearing minus your heading, in degrees; positive is to the right. */
    fun side(relative: Double): Side {
        val a = normalize(relative)
        val off = abs(a)
        val right = a > 0
        return when {
            off <= 25 -> Side.AHEAD
            off <= 60 -> if (right) Side.SLIGHTLY_RIGHT else Side.SLIGHTLY_LEFT
            off <= 120 -> if (right) Side.RIGHT else Side.LEFT
            off <= 155 -> if (right) Side.BEHIND_RIGHT else Side.BEHIND_LEFT
            else -> Side.BEHIND
        }
    }

    /** [side], but keeps [previous] while the angle is within [HYSTERESIS_DEG] of its range. */
    fun side(relative: Double, previous: Side?): Side {
        val now = side(relative)
        if (previous == null || now == previous) return now
        val near = side(relative - HYSTERESIS_DEG) == previous || side(relative + HYSTERESIS_DEG) == previous
        return if (near) previous else now
    }

    /** -180 (exclusive) to 180 (inclusive). */
    private fun normalize(degrees: Double): Double {
        val d = ((degrees % 360) + 360) % 360
        return if (d > 180) d - 360 else d
    }

    /** The line, the side it used (kept for the next update's hysteresis) and its [category]: the
     * rule that made it plus its side or compass point. TalkBack hears the line again only when the
     * category changes, never for a new number alone. */
    data class Line(val text: String, val side: Side? = null, val category: String = text)

    /** The direction line (spec C), first matching rule; null when location is off or not known yet.
     * [heading]: degrees from north, null without a compass reading. */
    fun line(
        you: LatLon?,
        accuracyM: Float?,
        heading: Double?,
        target: LatLon,
        noun: Noun,
        units: Units,
        previous: Side? = null,
    ): Line? {
        if (you == null) return null
        if (accuracyM != null && accuracyM > ROUGH_M) return Line(ROUGH)
        val meters = Places.meters(you, target)
        if (meters < AT_IT_M || (accuracyM != null && meters < accuracyM)) return Line(atIt(noun))
        val d = distance(meters, units)
        val bearing = Places.bearing(you, target)
        if (heading == null) {
            val point = Places.compass(bearing)
            return Line("$d $point of you", category = "compass $point")
        }
        val side = side(bearing - heading, previous)
        return Line(side.text(d), side, "side ${side.name}")
    }

    const val ROUGH = "Your location is rough right now. Use the map to find the big pin."
    fun atIt(noun: Noun) = "You're at it. It's the ${noun.one} at the big pin."

    /** How a neighbour is drawn (spec B). */
    enum class Kind { OPEN, DONE, LOOK }

    data class Neighbour(val id: Long, val position: LatLon, val kind: Kind)

    /** The campaign's other tasks with a position: checked ones (on the server or here) as
     * [Kind.DONE], needs-another-look ones (status 6, or reported here) as [Kind.LOOK], the rest of
     * created and skipped ones as [Kind.OPEN]. Deleted and other closed tasks aren't drawn. */
    fun neighbours(targetId: Long, tasks: List<LocatedTask>, done: Set<Long>, look: Set<Long>): List<Neighbour> =
        tasks.mapNotNull { t ->
            val position = t.position ?: return@mapNotNull null
            if (t.id == targetId) return@mapNotNull null
            val kind = when {
                t.isDone || t.id in done -> Kind.DONE
                t.task.status?.code == NEEDS_LOOK || t.id in look -> Kind.LOOK
                t.task.status?.code in OPEN -> Kind.OPEN
                else -> return@mapNotNull null
            }
            Neighbour(t.id, position, kind)
        }

    private const val NEEDS_LOOK = 6
    /** Created and skipped: still to check. */
    private val OPEN = setOf(0, 3)

    /** The first frame (spec A): you and the target, or the target alone at [TARGET_ZOOM] when
     * your position is unknown or farther than [FAR_M]. */
    fun frame(you: LatLon?, target: LatLon): List<LatLon> =
        if (you == null || Places.meters(you, target) > FAR_M) listOf(target) else listOf(you, target)
}
