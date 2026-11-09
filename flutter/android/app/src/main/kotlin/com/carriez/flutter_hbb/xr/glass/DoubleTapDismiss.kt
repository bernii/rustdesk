package com.carriez.flutter_hbb.xr.glass

import kotlin.math.hypot

/** Two completed single-finger taps, never a tap followed by a drag or hold. */
class DoubleTapDismiss(private val slop: Float, private val distance: Float, private val timeout: Long) {
    private var downAt = -1L
    private var downX = 0f
    private var downY = 0f
    private var tapAt = -1L
    private var tapX = 0f
    private var tapY = 0f
    private var second = false

    fun begin(x: Float, y: Float, time: Long) {
        second = tapAt >= 0 && time - tapAt in 0..timeout && hypot(x - tapX, y - tapY) <= distance
        downAt = time
        downX = x
        downY = y
    }

    fun move(x: Float, y: Float) {
        if (downAt >= 0 && hypot(x - downX, y - downY) > slop) cancel()
    }

    fun end(x: Float, y: Float, time: Long): Boolean {
        move(x, y)
        if (downAt < 0 || time - downAt !in 0..timeout) {
            cancel()
            return false
        }
        val dismiss = second
        cancel()
        if (!dismiss) {
            tapAt = time
            tapX = x
            tapY = y
        }
        return dismiss
    }

    fun cancel() {
        downAt = -1
        tapAt = -1
        second = false
    }
}
