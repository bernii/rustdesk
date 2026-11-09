package com.carriez.flutter_hbb.xr.glass

import android.view.MotionEvent
import kotlin.math.hypot

const val MAX_FINGERS = 5

/** A frame's worth of fingers, copied out for the GL thread. */
class FrameFingers {
    var count = 0
    /** Which touch each finger is, unique for as long as the app runs. */
    val touch = IntArray(MAX_FINGERS)
    val fromX = FloatArray(MAX_FINGERS)
    val fromY = FloatArray(MAX_FINGERS)
    val toX = FloatArray(MAX_FINGERS)
    val toY = FloatArray(MAX_FINGERS)
    val major = FloatArray(MAX_FINGERS)
    val minor = FloatArray(MAX_FINGERS)
    val angle = FloatArray(MAX_FINGERS)
    val speed = FloatArray(MAX_FINGERS)
    val kick = BooleanArray(MAX_FINGERS)
}

/**
 * Touches, written on the UI thread and read once per frame on the GL thread.
 * Positions are px with y down. Each finger carries its contact ellipse as
 * MotionEvent reports it: major and minor axes in px and the major axis's
 * angle in radians clockwise from vertical.
 */
class Fingers {
    private class Finger {
        var id = -1
        var touch = 0
        var x = 0f
        var y = 0f
        var fromX = 0f
        var fromY = 0f
        var major = 0f
        var minor = 0f
        var angle = 0f
        var speed = 0f
        var downAt = 0L
        var upAt = Long.MAX_VALUE
        var kick = false
    }

    private val slots = Array(MAX_FINGERS) { Finger() }
    private var touches = 0

    /** System.nanoTime() of the last touch event, or of the last lift. */
    @Volatile
    var lastActivity = 0L
        private set

    fun onTouchEvent(e: MotionEvent) {
        synchronized(this) { handle(e) }
    }

    private fun handle(e: MotionEvent) {
        val now = System.nanoTime()
        lastActivity = now
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val slot = slots.firstOrNull { it.id < 0 } ?: return
                slot.id = e.getPointerId(i)
                slot.touch = ++touches
                slot.x = e.getX(i)
                slot.y = e.getY(i)
                slot.fromX = slot.x
                slot.fromY = slot.y
                slot.major = 0f
                slot.minor = 0f
                slot.speed = 0f
                slot.downAt = now
                slot.upAt = Long.MAX_VALUE
                slot.kick = true
                updateContact(slot, e, i)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val slot = find(e.getPointerId(i)) ?: continue
                    slot.x = e.getX(i)
                    slot.y = e.getY(i)
                    updateContact(slot, e, i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                find(e.getPointerId(i))?.let { lift(it, now) }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (f in slots) if (f.id >= 0) lift(f, now)
            }
        }
    }

    /** True when no finger is down or still lifting. */
    fun isIdle() = synchronized(this) { slots.none { it.id >= 0 } }

    /**
     * Copies the fingers into [out] for this frame, sweeping each from where
     * it was last frame, then starts the next sweep where this one ends.
     * Lifted fingers are simulated once more, then released.
     */
    fun takeFrame(now: Long, simDt: Float, density: Float, out: FrameFingers) {
        synchronized(this) { copyFrame(now, simDt, density, out) }
    }

    private fun copyFrame(now: Long, simDt: Float, density: Float, out: FrameFingers) {
        var n = 0
        for (f in slots) {
            if (f.id < 0) continue
            val moved = hypot(f.x - f.fromX, f.y - f.fromY) / density
            f.speed += ((if (simDt > 0f) moved / simDt else 0f) - f.speed) * 0.35f // dp per sim second
            out.touch[n] = f.touch
            out.fromX[n] = f.fromX
            out.fromY[n] = f.fromY
            out.toX[n] = f.x
            out.toY[n] = f.y
            out.major[n] = f.major
            out.minor[n] = f.minor
            out.angle[n] = f.angle
            out.speed[n] = f.speed
            out.kick[n] = f.kick
            n++
            f.kick = false
            f.fromX = f.x
            f.fromY = f.y
            if (now >= f.upAt) {
                f.id = -1
                lastActivity = now
            }
        }
        out.count = n
    }

    private fun find(id: Int) = slots.firstOrNull { it.id == id }

    // A click still touches the oil for MIN_CONTACT_NANOS.
    private fun lift(f: Finger, now: Long) {
        f.upAt = maxOf(now, f.downAt + MIN_CONTACT_NANOS)
    }

    // Contact axes are smoothed a little; touch controllers quantise them.
    private fun updateContact(f: Finger, e: MotionEvent, i: Int) {
        val major = e.getTouchMajor(i)
        val minor = e.getTouchMinor(i).takeIf { it > 0f } ?: major
        if (major <= 0f) return
        if (f.major <= 0f) {
            f.major = major
            f.minor = minor
        } else {
            f.major += (major - f.major) * 0.5f
            f.minor += (minor - f.minor) * 0.5f
        }
        f.angle = e.getOrientation(i)
    }

    private companion object {
        const val MIN_CONTACT_NANOS = 70_000_000L
    }
}
