package lol.osm.streettally.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.PathParser

/** The brand map pins (brand/pins/svg, light versions), drawn at their 28 × 28 viewBox scaled by
 * [density]. Each state differs in shape as well as color, as on iOS and the live page. */
internal enum class Pin(val id: String) {
    /** To check: amber circle, navy ring. */
    TODO("pin-todo"),
    /** Checked: green circle, white ring and tick. */
    DONE("pin-done"),
    /** A guest's answer, saved but not on the map yet: white rounded square, dashed green edge, tick. */
    PENDING("pin-pending"),
    /** Needs another look: amber diamond, navy edge, navy "?". */
    LOOK("pin-look"),
    /** A guest's answer still on this phone, not sent yet: white rounded square, dashed grey edge,
     * three dots, no tick (offline-copy.md). */
    WAITING("pin-waiting"),
    ;

    /** [dark]: for the dark map, where navy vanishes ("Dark map" 2): the to-check ring turns white, and
     * the needs-another-look diamond gets a white halo. */
    fun bitmap(density: Float, dark: Boolean = false): Bitmap {
        val size = (SIZE * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(density, density)
        when (this) {
            TODO -> {
                canvas.drawCircle(14f, 14f, 10.5f, fill(AMBER))
                if (dark) {
                    // The 2 dp white ring wholly outside the disc, edged in black so it still shows on
                    // the close-up's white building outline and crossing bar (Iris, #37).
                    canvas.drawCircle(14f, 14f, 13f, stroke(BLACK, 1f))
                    canvas.drawCircle(14f, 14f, 11.5f, stroke(WHITE, 2f))
                } else {
                    canvas.drawCircle(14f, 14f, 10.5f, stroke(NAVY, 3f))
                }
            }
            DONE -> {
                canvas.drawCircle(14f, 14f, 12f, fill(GREEN))
                canvas.drawCircle(14f, 14f, 12f, stroke(WHITE, 2f))
                canvas.drawPath(PathParser.createPathFromPathData(TICK), stroke(WHITE, 2.6f))
            }
            PENDING -> {
                val square = RectF(3f, 3f, 25f, 25f)
                canvas.drawRoundRect(square, 6f, 6f, fill(WHITE))
                canvas.drawRoundRect(square, 6f, 6f, stroke(GREEN, 2.5f).apply { pathEffect = DashPathEffect(floatArrayOf(4.2f, 2.6f), 0f) })
                canvas.drawPath(PathParser.createPathFromPathData(TICK), stroke(GREEN, 2.6f))
            }
            LOOK -> {
                canvas.save()
                canvas.rotate(45f, 14f, 14f)
                val diamond = RectF(6f, 6f, 22f, 22f)
                if (dark) canvas.drawRoundRect(diamond, 2.6f, 2.6f, stroke(WHITE, 6.4f))
                canvas.drawRoundRect(diamond, 2.6f, 2.6f, fill(AMBER))
                canvas.drawRoundRect(diamond, 2.6f, 2.6f, stroke(NAVY, 2.4f))
                canvas.restore()
                canvas.drawPath(PathParser.createPathFromPathData(QUESTION), stroke(NAVY, 2.1f))
                canvas.drawCircle(14f, 18.6f, 1.25f, fill(NAVY))
            }
            WAITING -> {
                val square = RectF(3f, 3f, 25f, 25f)
                val grey = if (dark) GREY_DARK else GREY
                canvas.drawRoundRect(square, 6f, 6f, fill(if (dark) INK else WHITE))
                canvas.drawRoundRect(square, 6f, 6f, stroke(grey, 2.5f).apply { pathEffect = DashPathEffect(floatArrayOf(4.2f, 2.6f), 0f) })
                for (x in floatArrayOf(9.5f, 14f, 18.5f)) canvas.drawCircle(x, 14f, 1.6f, fill(grey))
            }
        }
        return bitmap
    }

    private companion object {
        const val SIZE = 28
        val AMBER = Color.parseColor("#F5A524")
        val NAVY = Color.parseColor("#14213D")
        val GREEN = Color.parseColor("#1F7A4D")
        val WHITE = Color.parseColor("#FFFFFF")
        val BLACK = Color.parseColor("#000000")
        val GREY = Color.parseColor("#8A93A6")
        val GREY_DARK = Color.parseColor("#6B7486")
        val INK = Color.parseColor("#0B1220")
        const val TICK = "M8.6 14.3 L12.4 18 L19.6 10.4"
        const val QUESTION = "M11.4 11.6 a2.7 2.7 0 1 1 3.9 2.4 c-0.9 0.45 -1.3 0.9 -1.3 1.9"

        fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
}

/** A cluster of places on a big campaign's map (L1-CLUSTER, Iris and Juno): a to-do circle in a navy
 * ring with the number still to check in white, or, once none is left, a PinDone circle with a tick.
 * Sized in three steps by the cluster's total. Drawn on demand when MapLibre asks for an id from [id]. */
internal object ClusterImage {
    const val PREFIX = "cluster-"

    fun id(total: Int, left: Int) = "$PREFIX$total-$left"

    /** The total and the number left in an [id], or null for any other image id. */
    fun parse(id: String): Pair<Int, Int>? {
        if (!id.startsWith(PREFIX)) return null
        val parts = id.removePrefix(PREFIX).split('-')
        if (parts.size != 2) return null
        val total = parts[0].toIntOrNull() ?: return null
        val left = parts[1].toIntOrNull()?.coerceIn(0, total) ?: return null
        return total to left
    }

    /** The diameter in dp, by the cluster's total: under 100, under 1,000, and 1,000 or more (as iOS). */
    fun diameter(total: Int): Float = when {
        total < 100 -> 36f
        total < 1_000 -> 44f
        else -> 52f
    }

    /** The count's bold text size in sp: 13, 13 and 14 for the three [diameter] steps. */
    fun textSize(total: Int): Float = if (total < 1_000) 13f else 14f

    /** [dark]: only the all-done circle changes, to the dark map's PinDone (bright green, ink tick and edge). */
    fun bitmap(total: Int, left: Int, density: Float, dark: Boolean = false, locale: java.util.Locale = java.util.Locale.getDefault()): Bitmap {
        val d = diameter(total)
        val size = (d * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(density, density)
        val c = d / 2
        if (left == 0) {
            val edge = 2f
            val ink = if (dark) INK else WHITE
            canvas.drawCircle(c, c, c - edge / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) GREEN_DARK else GREEN })
            canvas.drawCircle(c, c, c - edge / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = edge; color = ink })
            canvas.save()
            val scale = d / 28f
            canvas.scale(scale, scale)
            canvas.drawPath(PathParser.createPathFromPathData(TICK), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2.6f; color = ink
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            })
            canvas.restore()
            return bitmap
        }
        val ring = 3f
        canvas.drawCircle(c, c, c - ring / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TO_CHECK })
        canvas.drawCircle(c, c, c - ring / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ring; color = NAVY })
        val text = lol.osm.streettally.campaign.number(left, locale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = textSize(total)
        }
        canvas.drawText(text, c, c - (paint.descent() + paint.ascent()) / 2, paint)
        return bitmap
    }

    /** accentText: white text on it is 5.6:1 (Iris: at least 4.5:1; white on the pins' #F5A524 is 2.0:1). */
    private val TO_CHECK = Color.parseColor("#A84F00")
    /** PinTodo's ring. */
    private val NAVY = Color.parseColor("#14213D")
    /** PinDone, light and dark map. */
    private val GREEN = Color.parseColor("#1F7A4D")
    private val GREEN_DARK = Color.parseColor("#34D399")
    private val WHITE = Color.parseColor("#FFFFFF")
    private val INK = Color.parseColor("#0B1220")
    private const val TICK = "M8.6 14.3 L12.4 18 L19.6 10.4"
}
