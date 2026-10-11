package lol.osm.streettally.campaign

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** Distance rules for "It's moved" (design/not-here-moved.md §3.2), as in the iOS MovedSpotView. */
object MovedSpot {
    /** Under this the pick is where the map already has it; Save is disabled. */
    const val TOO_CLOSE_M = 5.0
    /** Over this the pick may be a different place; Save still works. */
    const val FAR_M = 150.0
    /** Over this it can't be the same place; Save is disabled (the server refuses it too). */
    const val TOO_FAR_M = 500.0
    /** "I'm standing at it" needs a fix at least this accurate. */
    const val GOOD_FIX_M = 50f

    enum class Check { TOO_CLOSE, OK, FAR, TOO_FAR }

    fun check(meters: Double): Check = when {
        meters < TOO_CLOSE_M -> Check.TOO_CLOSE
        meters > TOO_FAR_M -> Check.TOO_FAR
        meters > FAR_M -> Check.FAR
        else -> Check.OK
    }

    fun canSave(meters: Double): Boolean = check(meters).let { it == Check.OK || it == Check.FAR }

    /** How far one TalkBack "Move …" action moves the new spot, as iOS. */
    const val NUDGE_M = 5.0

    /** [spot] moved [northM] meters north and [eastM] meters east (negative: south, west). */
    fun nudge(spot: LatLon, northM: Double = 0.0, eastM: Double = 0.0): LatLon = LatLon(
        spot.lat + northM / METERS_PER_DEGREE,
        spot.lon + eastM / (METERS_PER_DEGREE * cos(spot.lat * PI / 180)),
    )

    /** TalkBack's value for the new spot: "About 30 m northeast of where the map has it", or "Where
     * the map has it" when it's under [TOO_CLOSE_M] away. */
    fun describe(mapped: LatLon, spot: LatLon): String {
        val meters = Places.meters(mapped, spot)
        if (meters < TOO_CLOSE_M) return "Where the map has it"
        return "About ${roundedMeters(meters)} m ${Places.compass(Places.bearing(mapped, spot))} of where the map has it"
    }

    private const val METERS_PER_DEGREE = 111_320.0

    /** Under 100 m to the nearest 5 m, then to the nearest 10 m. */
    fun roundedMeters(meters: Double): Int {
        val step = if (meters < 100) 5 else 10
        return (meters / step).roundToInt() * step
    }
}
