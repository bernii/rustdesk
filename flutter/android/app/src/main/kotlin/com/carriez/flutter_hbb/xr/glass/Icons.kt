package com.carriez.flutter_hbb.xr.glass

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The Trackpad design's icons, drawn with a Canvas from its SVG geometry:
 * into the GL atlas for the top row, and by the views for the panel and the
 * text field. Each draws its viewBox into a square or box of the given size.
 */
enum class Icon(val viewW: Float, val viewH: Float = viewW) {
    BACK(24f),
    SPEED(22f, 14f),
    WAKE(24f),
    CENTER(24f),
    LOCK(24f),
    UNLOCK(24f),
    SETTINGS(24f),
    KEYBOARD(24f),
    MIC(24f),
    CLEAR(24f),
    SLOWER(14f),
    FASTER(22f, 14f),
    NEAR(16f, 12f),
    FAR(16f, 12f),
    FLAT(16f, 12f),
    CURVED(16f, 12f),
    GLASSES(120f, 84f),
    ONE_FINGER(44f),
    TWO_FINGERS(44f),
    THREE_FINGERS(44f),
    DICTATE(44f),
    PINCH(44f);

    /** Draws into the box at (left, top), w × h px, in [color]. */
    fun draw(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, color: Int) {
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(w / viewW, h / viewH)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val path = Path()
        when (this) {
            BACK -> {
                stroke.strokeWidth = 2.2f
                path.moveTo(15f, 5f)
                path.lineTo(8f, 12f)
                path.lineTo(15f, 19f)
                canvas.drawPath(path, stroke)
            }
            SPEED -> {
                canvas.drawCircle(3f, 7f, 1.6f, fill)
                canvas.drawCircle(9.5f, 7f, 2.4f, fill)
                canvas.drawCircle(17.5f, 7f, 3.3f, fill)
            }
            WAKE -> {
                stroke.strokeWidth = 2f
                // A circle of 8 around the middle, from three o'clock most of
                // the way round, and its arrowhead.
                path.arcTo(RectF(4f, 4f, 20f, 20f), 0f, 312.5f, true)
                path.moveTo(20f, 4f)
                path.lineTo(20f, 9f)
                path.lineTo(15f, 9f)
                canvas.drawPath(path, stroke)
            }
            CENTER -> {
                stroke.strokeWidth = 2f
                canvas.drawCircle(12f, 12f, 3.2f, stroke)
                canvas.drawLine(12f, 2.5f, 12f, 6.5f, stroke)
                canvas.drawLine(12f, 17.5f, 12f, 21.5f, stroke)
                canvas.drawLine(2.5f, 12f, 6.5f, 12f, stroke)
                canvas.drawLine(17.5f, 12f, 21.5f, 12f, stroke)
            }
            LOCK, UNLOCK -> {
                stroke.strokeWidth = 2f
                canvas.drawRoundRect(RectF(5f, 11f, 19f, 21f), 2.5f, 2.5f, stroke)
                path.moveTo(8f, 11f)
                path.lineTo(8f, 8f)
                if (this == LOCK) {
                    path.arcTo(RectF(8f, 4f, 16f, 12f), 180f, 180f)
                    path.lineTo(16f, 11f)
                } else {
                    path.arcTo(RectF(8f, 4f, 16f, 12f), 180f, 153.4f)
                }
                canvas.drawPath(path, stroke)
            }
            SETTINGS -> {
                stroke.strokeWidth = 1.8f
                // Eight teeth, in the same rounded outline weight as the top icons.
                for (i in 0 until 32) {
                    val angle = i * Math.PI / 16 - Math.PI / 2
                    val radius = if (i % 4 == 1 || i % 4 == 2) 10f else 7.8f
                    val x = 12f + radius * kotlin.math.cos(angle).toFloat()
                    val y = 12f + radius * kotlin.math.sin(angle).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, stroke)
                canvas.drawCircle(12f, 12f, 3.1f, stroke)
            }
            KEYBOARD -> {
                stroke.strokeWidth = 1.7f
                canvas.drawRoundRect(RectF(2.5f, 6f, 21.5f, 18f), 2.5f, 2.5f, stroke)
                for (x in floatArrayOf(6.5f, 10f, 13.5f, 17.5f)) canvas.drawLine(x, 10f, x + 0.01f, 10f, stroke)
                canvas.drawLine(7.5f, 14f, 16.5f, 14f, stroke)
            }
            MIC -> {
                stroke.strokeWidth = 2f
                canvas.drawRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, stroke)
                path.arcTo(RectF(5.5f, 4.5f, 18.5f, 17.5f), 180f, -180f, true)
                path.moveTo(12f, 17.5f)
                path.lineTo(12f, 21f)
                canvas.drawPath(path, stroke)
            }
            CLEAR -> {
                fill.color = 0x29000000
                canvas.drawCircle(12f, 12f, 10f, fill)
                stroke.strokeWidth = 2f
                canvas.drawLine(8.5f, 8.5f, 15.5f, 15.5f, stroke)
                canvas.drawLine(15.5f, 8.5f, 8.5f, 15.5f, stroke)
            }
            SLOWER -> canvas.drawCircle(7f, 7f, 2.2f, fill)
            FASTER -> {
                fill.alpha = (0.35f * 255).toInt()
                canvas.drawCircle(3f, 7f, 1.4f, fill)
                fill.alpha = (0.6f * 255).toInt()
                canvas.drawCircle(9f, 7f, 2f, fill)
                fill.alpha = 255
                canvas.drawCircle(17f, 7f, 3.4f, fill)
            }
            // Mac XR's slider ends (SliderEndIcon): a near and a far screen, flat and curved.
            NEAR -> {
                stroke.strokeWidth = 1.5f
                canvas.drawRoundRect(RectF(1f, 1.5f, 15f, 10.5f), 1.5f, 1.5f, stroke)
            }
            FAR -> {
                stroke.strokeWidth = 1.5f
                canvas.drawRoundRect(RectF(5f, 4f, 11f, 8f), 1f, 1f, stroke)
            }
            FLAT -> {
                stroke.strokeWidth = 1.5f
                canvas.drawLine(1f, 6f, 15f, 6f, stroke)
            }
            CURVED -> {
                stroke.strokeWidth = 1.5f
                path.moveTo(1f, 9.5f)
                path.quadTo(8f, -0.5f, 15f, 9.5f)
                canvas.drawPath(path, stroke)
            }
            GLASSES -> {
                // Mac XR's mark (LogoMark): one frame with a bite out of its
                // lower-right corner and a temple on the left. The opening
                // winds the other way, so the fill leaves it open.
                fun arc(cx: Float, cy: Float, r: Float, from: Float, to: Float) =
                    path.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), from, to - from, false)
                canvas.translate(14f, 0f)
                path.moveTo(83f, 84f)
                path.lineTo(27f, 84f)
                arc(27f, 57f, 27f, 90f, 180f)
                path.lineTo(0f, 11f)
                arc(11f, 11f, 11f, 180f, 270f)
                path.lineTo(92f, 0f)
                arc(92f, 14f, 14f, 270f, 360f)
                path.lineTo(106f, 61f)
                arc(106f, 84f, 23f, 270f, 180f)
                path.close()
                path.moveTo(72.43f, 71f)
                arc(106f, 84f, 36f, 201.17f, 248.83f)
                path.lineTo(93f, 23f)
                arc(83f, 23f, 10f, 360f, 270f)
                path.lineTo(17f, 13f)
                arc(17f, 17f, 4f, 270f, 180f)
                path.lineTo(13f, 51f)
                arc(33f, 51f, 20f, 180f, 90f)
                path.close()
                path.moveTo(-14f, 8f)
                path.lineTo(6.5f, 8f)
                path.lineTo(6.5f, 38f)
                path.lineTo(0f, 38f)
                arc(-9f, 38f, 9f, 0f, -90f)
                path.lineTo(-14f, 29f)
                path.close()
                canvas.drawPath(path, fill)
            }
            ONE_FINGER, TWO_FINGERS, THREE_FINGERS, DICTATE, PINCH -> {
                // A dotted ring: dots of 2.2 every 5.236 round a circle of 20.
                for (i in 0 until 24) {
                    val a = Math.toRadians(i * 15.0)
                    canvas.drawCircle(22f + 20f * kotlin.math.cos(a).toFloat(), 22f + 20f * kotlin.math.sin(a).toFloat(), 1.1f, fill)
                }
                when (this) {
                    ONE_FINGER -> canvas.drawCircle(22f, 22f, 3.6f, fill)
                    TWO_FINGERS -> {
                        canvas.drawCircle(16.5f, 22f, 3.6f, fill)
                        canvas.drawCircle(27.5f, 22f, 3.6f, fill)
                    }
                    THREE_FINGERS -> {
                        canvas.drawCircle(13f, 22f, 3.2f, fill)
                        canvas.drawCircle(22f, 22f, 3.2f, fill)
                        canvas.drawCircle(31f, 22f, 3.2f, fill)
                    }
                    DICTATE -> {
                        stroke.strokeWidth = 1.7f
                        canvas.drawCircle(16.5f, 22f, 3.2f, stroke)
                        canvas.drawCircle(27.5f, 22f, 3.2f, stroke)
                    }
                    else -> {
                        canvas.drawCircle(19f, 25f, 2.6f, fill)
                        canvas.drawCircle(25f, 19f, 2.6f, fill)
                        canvas.drawCircle(14.5f, 29.5f, 1.5f, fill)
                        canvas.drawCircle(29.5f, 14.5f, 1.5f, fill)
                    }
                }
            }
        }
        canvas.restore()
    }
}
