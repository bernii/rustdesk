package com.carriez.flutter_hbb.xr.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint

/** The design's type: the system sans at its weights, sized in dp. */
object Type {
    val semibold: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    val medium: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)

    fun paint(typeface: Typeface, sizeDp: Float, density: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = sizeDp * density
        this.color = color
        isSubpixelText = true
    }

    const val INK = 0xFF0B0B0C.toInt()
    const val LEGEND_INK = 0xFF1C1C1E.toInt()
    const val SECONDARY = 0xFF4A4A50.toInt()
}

/**
 * The gesture legend that hangs in the oil, as the design draws it: a soft
 * white glow, and four rows of an icon and its words, in a 380 × 300 dp box
 * centered on the pad. The oil's composite samples it beside the dots.
 */
object Legend {
    const val WIDTH_DP = 380f
    const val HEIGHT_DP = 300f

    private val rows = listOf(
        Icon.ONE_FINGER to "Move, tap to click",
        Icon.TWO_FINGERS to "Two fingers to scroll, tap to right-click",
        Icon.THREE_FINGERS to "Three fingers to drag, lift one to drop",
        Icon.DICTATE to "Double-tap with two fingers to dictate",
        Icon.PINCH to "Dictating, two fingers: lift to send, pinch in for Enter, out to discard",
    )

    fun draw(density: Float): Bitmap {
        val w = (WIDTH_DP * density).toInt()
        val h = (HEIGHT_DP * density).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // radial-gradient(closest-side, white 50%, transparent): an ellipse
        // touching the box's sides.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(0f, 0f, w / 2f, Color.argb(128, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.scale(1f, h.toFloat() / w)
        canvas.drawCircle(0f, 0f, w / 2f, glow)
        canvas.restore()

        val text = TextPaint(Type.paint(Type.semibold, 15f, density, Type.LEGEND_INK)).apply {
            setShadowLayer(6f * density, 0f, 0f, Color.argb(191, 255, 255, 255))
        }
        val icon = 44f * density
        val gap = 14f * density
        val listW = 304f * density
        val textW = (listW - icon - gap).toInt()
        // Words wrap at 20 dp lines, each row as tall as its icon or its words.
        val layouts = rows.map { (_, words) ->
            StaticLayout.Builder.obtain(words, 0, words.length, text, textW)
                .setLineSpacing(20f * density - text.fontSpacing, 1f)
                .setIncludePad(false)
                .build()
        }
        val heights = layouts.map { maxOf(icon, it.height.toFloat()) }
        var top = (h - heights.sum() - (rows.size - 1) * gap) / 2f
        val left = (w - listW) / 2f
        rows.forEachIndexed { i, (kind, _) ->
            val rowH = heights[i]
            kind.draw(canvas, left, top + (rowH - icon) / 2f, icon, icon, Type.LEGEND_INK)
            canvas.save()
            canvas.translate(left + icon + gap, top + (rowH - layouts[i].height) / 2f)
            layouts[i].draw(canvas)
            canvas.restore()
            top += rowH + gap
        }
        return bitmap
    }
}
