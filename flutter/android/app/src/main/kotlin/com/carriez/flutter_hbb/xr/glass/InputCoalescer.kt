package com.carriez.flutter_hbb.xr.glass

/**
 * Unbuffered touches can arrive faster than the display, and each would cost a
 * platform-channel call and a network message. Pointer and scroll motion are
 * summed and sent once per animation frame; [flush] sends them early, so a button
 * event never overtakes the motion before it. Scrolling is sent in whole pixels,
 * keeping the remainder, so a slow coast isn't rounded away.
 */
class InputCoalescer(
    private val post: (Runnable) -> Unit,
    private val send: (String, Map<String, Any>) -> Unit,
) {
    private var moveX = 0f
    private var moveY = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    private var pending = false
    private val onFrame = Runnable {
        pending = false
        flush()
    }

    fun move(dx: Float, dy: Float) {
        moveX += dx
        moveY += dy
        schedule()
    }

    fun scroll(dx: Float, dy: Float) {
        scrollX += dx
        scrollY += dy
        schedule()
    }

    fun flush() {
        if (moveX != 0f || moveY != 0f) {
            send("move", mapOf("dx" to moveX.toDouble(), "dy" to moveY.toDouble()))
            moveX = 0f
            moveY = 0f
        }
        val x = scrollX.toInt()
        val y = scrollY.toInt()
        if (x != 0 || y != 0) {
            send("scroll", mapOf("dx" to x, "dy" to y))
            scrollX -= x
            scrollY -= y
        }
    }

    /** A gesture ended: what's left of a pixel doesn't carry into the next one. */
    fun settle() {
        flush()
        scrollX = 0f
        scrollY = 0f
    }

    private fun schedule() {
        if (pending) return
        pending = true
        post(onFrame)
    }
}
