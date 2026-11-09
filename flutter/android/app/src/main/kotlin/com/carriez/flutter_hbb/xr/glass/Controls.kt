package com.carriez.flutter_hbb.xr.glass

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeInfo.RangeInfo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round

/**
 * A round button that draws one of the design's icons, [iconDp] wide, in
 * [tint], over an optional [disc]. Pressing dims it.
 */
@SuppressLint("ViewConstructor")
class IconButton(
    context: Context,
    var icon: Icon,
    private val iconDp: Float,
    private val unit: Float,
    label: String,
    var tint: Int = Type.INK,
    private val disc: Int = Color.TRANSPARENT,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = disc }
    /** Something to draw instead of the icon, centered, or null. */
    var instead: ((Canvas, Float, Float) -> Unit)? = null

    /** Drawn by the glass instead (UiPass), so only its touch is here. */
    var hidden = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        contentDescription = label
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        if (hidden) return
        val cx = width / 2f
        val cy = height / 2f
        if (Color.alpha(disc) > 0) canvas.drawCircle(cx, cy, minOf(width, height) / 2f, fill)
        val draw = instead
        if (draw != null) {
            draw(canvas, cx, cy)
        } else {
            val w = iconDp * unit
            val h = w * icon.viewH / icon.viewW
            icon.draw(canvas, cx - w / 2f, cy - h / 2f, w, h, tint)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        alpha = if (isPressed) 0.6f else 1f
    }
}

/** An icon on its own, [wDp] × [hDp] of the design. */
@SuppressLint("ViewConstructor")
class IconView(context: Context, private val icon: Icon, private val unit: Float) : View(context) {
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) = icon.draw(canvas, 0f, 0f, width.toFloat(), height.toFloat(), Type.INK)
}

/** [raw] on the grid of [step]s up from [min], within [min] and [max]. */
fun stepped(raw: Float, min: Float, max: Float, step: Float) = (min + round((raw - min) / step) * step).coerceIn(min, max)

/**
 * A slider in the design's look: a dark fill up to the thumb, a tick at each
 * of [ticks] that darkens once passed, a white thumb, and an optional [caption]
 * under the track at a value. With [insetThumb] off the thumb's center spans
 * the whole track, as the pointer speed's input, which overhangs its box by
 * half a thumb each side, gives it. With it on the thumb stays inside the
 * view, whole at either end, for a slider whose ends are ordinary settings.
 * [nudge] is how far an accessibility scroll moves it.
 */
@SuppressLint("ViewConstructor")
class GlassSlider(
    context: Context,
    private val unit: Float,
    label: String,
    private val min: Float,
    private val max: Float,
    private val step: Float,
    private val nudge: Float = step,
    private val ticks: List<Float> = emptyList(),
    private val caption: Pair<Float, String>? = null,
    insetThumb: Boolean = false,
    private val snap: (Float) -> Float = { it },
    private val describe: (Float) -> String,
    private val onChange: (Float) -> Unit,
) : View(context) {
    var value = min
        set(v) {
            field = v
            invalidate()
        }

    /** A finger has this slider, so a change arriving from elsewhere is out of date. */
    var grabbed = false
        private set
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val inset = if (insetThumb) 18f * unit else 0f
    private val captionHeight = if (caption == null) 0f else CAPTION_DP * unit
    private val captionPaint = Type.paint(Type.medium, 11f, unit, Type.SECONDARY).apply { textAlign = Paint.Align.CENTER }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24000000 }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Type.INK }
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(4f * unit, 0f, 3f * unit, 0x3D000000)
    }
    private val thumbEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * unit
        color = 0x24000000
    }

    init {
        isFocusable = true
        contentDescription = label
    }

    private fun x(v: Float) = inset + (v - min) / (max - min) * (width - 2f * inset)

    override fun onDraw(canvas: Canvas) {
        val cy = (height - captionHeight) / 2f
        val line = 2f * unit
        val from = x(min)
        canvas.drawRoundRect(from, cy - line / 2f, x(max), cy + line / 2f, line / 2f, line / 2f, track)
        val at = x(value)
        canvas.drawRoundRect(from, cy - line / 2f, at, cy + line / 2f, line / 2f, line / 2f, ink)
        for (tick in ticks) canvas.drawCircle(x(tick), cy, 3.5f * unit, if (tick <= value) ink else faint)
        caption?.let { (where, text) -> canvas.drawText(text, x(where), height - 2f * unit, captionPaint) }
        canvas.drawCircle(at, cy, 14f * unit, thumb)
        canvas.drawCircle(at, cy, 14f * unit, thumbEdge)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Inside a scrolling panel a finger may land here on its way to scrolling,
        // so only a sideways move or a tap takes the slider.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!grabbed && abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY)) {
                    grabbed = true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                if (grabbed) set(valueAt(e.x))
            }
            MotionEvent.ACTION_UP -> {
                set(valueAt(e.x))
                grabbed = false
            }
            MotionEvent.ACTION_CANCEL -> grabbed = false
        }
        return true
    }

    private fun valueAt(x: Float) = min + ((x - inset) / (width - 2f * inset)).coerceIn(0f, 1f) * (max - min)

    private fun set(raw: Float) {
        val next = snap(stepped(raw, min, max, step))
        if (next == value) return
        value = next
        onChange(next)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = RangeInfo.obtain(RangeInfo.RANGE_TYPE_FLOAT, min, max, value)
        info.stateDescription = describe(value)
        if (value > min) info.addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (value < max) info.addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> set(value + nudge)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> set(value - nudge)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }

    companion object {
        /** The room a [caption] takes under the track, dp of the design. */
        const val CAPTION_DP = 14f
    }
}

/** An on-off switch in the sliders' look: an ink track when on, a faint one when off, and a white thumb. */
@SuppressLint("ViewConstructor")
class GlassSwitch(context: Context, private val unit: Float) : View(context) {
    private var position = 0f
    private var motion: ValueAnimator? = null

    var checked = false
        set(v) {
            if (field == v) return
            field = v
            motion?.cancel()
            val goal = if (v) 1f else 0f
            if (!isShown) {
                position = goal
                invalidate()
                return
            }
            motion = ValueAnimator.ofFloat(position, goal).apply {
                duration = 150
                addUpdateListener {
                    position = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24000000 }
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Type.INK }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(1.5f * unit, 0f, 1f * unit, 0x3D000000)
    }

    init {
        // The row it sits in is what's read and touched.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        canvas.drawRoundRect(0f, 0f, w, h, r, r, off)
        on.alpha = (position * 255).toInt()
        canvas.drawRoundRect(0f, 0f, w, h, r, r, on)
        canvas.drawCircle(r + position * (w - h), r, r - 2.5f * unit, thumb)
    }
}

/**
 * The screen seen from above, as Mac XR's panel draws it (ScreenTopView), in
 * the gain curve's look: the wearer facing right, the wedge of what they can
 * see, marks at 1×, 2× and 3×, and the screen, curving around them with
 * [ScreenSettings]' geometry.
 */
@SuppressLint("ViewConstructor")
class ScreenTopView(context: Context, private val unit: Float) : View(context) {
    private var distance = ScreenSettings.DISTANCE_ONE_TO_ONE
    private var curvature = ScreenSettings.CURVATURE_FLAT

    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8CFFFFFF.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = unit
        color = 0x0F000000
    }
    private val wedge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0F000000 }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = unit
        color = 0x47000000
        pathEffect = DashPathEffect(floatArrayOf(2f * unit, 4f * unit), 0f)
    }
    private val screen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * unit
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Type.INK
    }
    private val wearer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Type.INK }
    private val mark = Type.paint(Type.medium, 11f, unit, Type.SECONDARY).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val clip = Path()
    private val box = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        show(distance, curvature)
    }

    fun show(distance: Float, curvature: Float) {
        this.distance = distance
        this.curvature = curvature
        contentDescription = ScreenSettings.describe(distance, curvature)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 28f * unit
        box.set(unit / 2f, unit / 2f, w - unit / 2f, h - unit / 2f)
        canvas.drawRoundRect(box, r, r, back)
        canvas.save()
        clip.reset()
        clip.addRoundRect(box, r, r, Path.Direction.CW)
        canvas.clipPath(clip)

        val originX = 22f * unit
        val originY = (h - 14f * unit) / 2f
        // The right margin keeps the 3× mark clear of the card's rounded corner.
        val scale = minOf((w - originX - 24f * unit) / 3f, (h - 24f * unit) / ScreenSettings.WIDTH)
        val spread = (w - originX) * ScreenSettings.HALF_VIEW
        path.reset()
        path.moveTo(originX, originY)
        path.lineTo(w, originY - spread)
        path.lineTo(w, originY + spread)
        path.close()
        canvas.drawPath(path, wedge)
        canvas.drawLine(originX + 10f * unit, originY, w - 4f * unit, originY, axis)
        for (k in 1..3) canvas.drawText("$k×", originX + k * scale, h - 5f * unit, mark)

        val arc = ScreenSettings.arc(distance, curvature)
        path.reset()
        path.moveTo(originX + arc[0] * scale, originY + arc[1] * scale)
        for (i in 2 until arc.size step 2) path.lineTo(originX + arc[i] * scale, originY + arc[i + 1] * scale)
        canvas.drawPath(path, screen)

        canvas.drawCircle(originX, originY, 6f * unit, wearer)
        path.reset()
        path.moveTo(originX + 7f * unit, originY - 3.5f * unit)
        path.lineTo(originX + 11.5f * unit, originY)
        path.lineTo(originX + 7f * unit, originY + 3.5f * unit)
        path.close()
        canvas.drawPath(path, wearer)
        canvas.restore()
        canvas.drawRoundRect(box, r, r, border)
    }
}

/** The glasses' mark on a disc: white on ink while they're connected, grey on a faint disc otherwise. */
@SuppressLint("ViewConstructor")
class GlassesBadge(context: Context, private val unit: Float) : View(context) {
    var connected = false
        set(v) {
            field = v
            disc.color = if (v) Type.INK else 0x14000000
            invalidate()
        }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000 }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, minOf(width, height) / 2f, disc)
        val w = 26f * unit
        val h = w * Icon.GLASSES.viewH / Icon.GLASSES.viewW
        Icon.GLASSES.draw(canvas, cx - w / 2f, cy - h / 2f, w, h, if (connected) Color.WHITE else Type.SECONDARY)
    }
}

/** Shape as well as color, as in Mac XR: a filled green dot while connected, a hollow ring otherwise. */
@SuppressLint("ViewConstructor")
class StatusDot(context: Context, private val unit: Float) : View(context) {
    var connected = false
        set(v) {
            field = v
            invalidate()
        }
    private val filled = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CONNECTED }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * unit
        color = Type.SECONDARY
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        if (connected) canvas.drawCircle(r, height / 2f, r, filled) else canvas.drawCircle(r, height / 2f, r - ring.strokeWidth / 2f, ring)
    }

    private companion object {
        // Mac XR's Brand.connected, for a light background.
        const val CONNECTED = 0xFF248A3D.toInt()
    }
}

/**
 * The gain curve: pointer gain against finger speed from 0 to 2 dp/ms at
 * the current setting, on a 0 to 24 px-per-dp scale, with the last move as a
 * dot on it. Captioned slow (precise) to fast (far).
 */
@SuppressLint("ViewConstructor")
class GainCurve(context: Context, private val unit: Float) : View(context) {
    var speed = 3f
        set(v) {
            field = v
            describe()
            invalidate()
        }
    var lastV: Float? = null
        set(v) {
            field = v
            invalidate()
        }

    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8CFFFFFF.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = unit
        color = 0x0F000000
    }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = unit
        color = 0x24000000
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0F000000 }
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * unit
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Type.INK
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val caption = Type.paint(Type.medium, 12f, unit, Type.SECONDARY)
    private val path = Path()
    private val fillPath = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        describe()
    }

    private fun describe() {
        contentDescription = "Pointer gain rises from %.2f to %.1f px per dp as the finger speeds up".format(
            TrackpadGesture.gain(0f) * speed, TrackpadGesture.gain(2f) * speed,
        )
    }

    private fun y(v: Float) = (92f - TrackpadGesture.gain(v) * speed / 24f * 88f) * unit

    override fun onDraw(canvas: Canvas) {
        val r = 28f * unit
        val box = RectF(unit / 2f, unit / 2f, width - unit / 2f, height - unit / 2f)
        canvas.drawRoundRect(box, r, r, back)
        canvas.drawRoundRect(box, r, r, border)
        canvas.save()
        canvas.translate(13f * unit, 13f * unit)
        canvas.drawLine(0f, 92f * unit, 292f * unit, 92f * unit, base)
        path.reset()
        for (i in 0..60) {
            val v = 2f * i / 60f
            val x = v / 2f * 292f * unit
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        fillPath.set(path)
        fillPath.lineTo(292f * unit, 92f * unit)
        fillPath.lineTo(0f, 92f * unit)
        fillPath.close()
        canvas.drawPath(fillPath, area)
        canvas.drawPath(path, curve)
        lastV?.let {
            val v = minOf(it, 2f)
            val x = v / 2f * 292f * unit
            canvas.drawCircle(x, y(v), 5f * unit, dot)
            canvas.drawCircle(x, y(v), 5f * unit, curve)
        }
        val baseline = (96f + 4f + 12f) * unit
        canvas.drawText("Slow finger: precise", 0f, baseline, caption)
        val fast = "Fast finger: far"
        canvas.drawText(fast, 292f * unit - caption.measureText(fast), baseline, caption)
        canvas.restore()
    }
}

/** Five dots that swell and fade in turn while dictation listens. */
@SuppressLint("ViewConstructor")
class LevelDots(context: Context, private val unit: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Type.INK }
    private val start = System.nanoTime()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val t = (System.nanoTime() - start) / 1e9f
        val cy = height / 2f
        for (i in 0 until 5) {
            // 0.5 to 1 and back over 0.9 s, each 0.15 s after the last.
            val phase = ((t - i * 0.15f) / 0.9f).mod(1f)
            val k = 0.5f - 0.5f * kotlin.math.cos(2f * PI.toFloat() * phase)
            paint.alpha = ((0.4f + 0.6f * k) * 255).toInt()
            canvas.drawCircle((3f + i * 9f) * unit, cy, 3f * unit * (0.5f + 0.5f * k), paint)
        }
        // A slow pulse: 30 frames a second is plenty, and spares the GPU.
        if (isShown) postInvalidateDelayed(33)
    }
}
