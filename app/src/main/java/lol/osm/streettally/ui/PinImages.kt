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

/** A cluster of places on a big campaign's map (L1-CLUSTER): an amber disc with the count in navy,
 * inside a ring whose green share is the part already checked, in tenths. Sized in three steps by
 * count. Drawn on demand when MapLibre asks for an id from [id]. */
internal object ClusterImage {
    const val PREFIX = "cluster-"

    fun id(count: Int, tenths: Int) = "$PREFIX$count-$tenths"

    /** The count and tenths in an [id], or null for any other image id. */
    fun parse(id: String): Pair<Int, Int>? {
        if (!id.startsWith(PREFIX)) return null
        val parts = id.removePrefix(PREFIX).split('-')
        val count = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val tenths = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 10) ?: return null
        return count to tenths
    }

    /** The diameter in dp: under 100, under 1,000, and 1,000 or more (the same steps as iOS). */
    fun diameter(count: Int): Float = when {
        count < 100 -> 36f
        count < 1_000 -> 44f
        else -> 52f
    }

    fun bitmap(count: Int, tenths: Int, density: Float, dark: Boolean, locale: java.util.Locale = java.util.Locale.getDefault()): Bitmap {
        val d = diameter(count)
        val size = (d * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(density, density)
        val c = d / 2
        val ring = 3.5f
        canvas.drawCircle(c, c, c - ring, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMBER })
        val track = RectF(ring / 2, ring / 2, d - ring / 2, d - ring / 2)
        canvas.drawOval(track, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = ring; color = if (dark) WHITE else NAVY
        })
        if (tenths > 0) {
            canvas.drawArc(track, -90f, 36f * tenths, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = ring; color = GREEN
            })
        }
        val text = lol.osm.streettally.campaign.number(count, locale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NAVY
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = if (text.length > 4) 11f else 13f
        }
        canvas.drawText(text, c, c - (paint.descent() + paint.ascent()) / 2, paint)
        return bitmap
    }

    private val AMBER = Color.parseColor("#F5A524")
    private val NAVY = Color.parseColor("#14213D")
    private val GREEN = Color.parseColor("#1F7A4D")
    private val WHITE = Color.parseColor("#FFFFFF")
}
