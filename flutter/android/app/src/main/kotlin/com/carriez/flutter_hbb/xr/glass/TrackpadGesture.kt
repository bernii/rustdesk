package com.carriez.flutter_hbb.xr.glass

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** What a two-finger touch does to a dictation it stopped. */
enum class DictationFinish { SEND, ENTER, DISCARD }

/**
 * Trackpad gestures in dp: touch-glass-ui's recognizer with RustDesk XR's input
 * contract (docs/XR_INPUT_AND_CALIBRATION_2026_11_06.md). The sink decides what
 * each gesture does.
 *
 * One finger moves the pointer and a tap clicks; holds and tap-then-move never
 * drag. Two fingers scroll, pinch, or tap to right-click; two two-finger taps in
 * quick succession dictate. Three fingers moving together drag, and lifting any
 * of them drops. While dictating, the next two-finger touch stops recognition:
 * lifting sends, pinching in sends Enter too, pinching out discards. Tracing an
 * infinity twice without lifting runs alongside the pointer.
 */
class TrackpadGesture(private val sink: Sink) {
    interface Sink {
        /** One finger moved (dx, dy) dp at [speed] dp/ms, which [gain] (px per dp) turns into pointer motion. */
        fun pointer(dx: Float, dy: Float, gain: Float, speed: Float, dragging: Boolean)
        fun click(x: Float, y: Float)
        /** Three fingers started moving: the left button goes down. */
        fun dragStart()
        fun dragEnd()
        fun secondaryClick(x: Float, y: Float)
        fun scroll(dx: Float, dy: Float, momentum: Boolean)
        fun zoom(factor: Float)
        fun dictate()
        fun isDictating(): Boolean
        fun stopDictation()
        fun finishDictation(how: DictationFinish)
        /** Two continuous infinity traces completed within four seconds. */
        fun infinityDouble()
        /** Scrolling, its momentum, or a pinch has ended. */
        fun settle()
    }

    private val infinity = InfinityGesture()
    private var count = 0
    private var maxCount = 0
    private var x = 0f
    private var y = 0f
    private var startX = 0f
    private var startY = 0f
    private var span = 0f
    private var startSpan = 0f
    private var started = 0L
    private var previousTime = 0L
    private var lastScrollTime = 0L
    private var moved = false
    private var dragging = false
    private var pinching = false
    private var scrolling = false
    private var dictationGesture = false
    private var dictationResolved = false
    private var speed = -1f
    private var secondaryTap = -1L
    private var secondaryX = 0f
    private var secondaryY = 0f

    /** The last two-finger scroll's velocity, dp/s, for momentum. */
    var velocityX = 0f
        private set
    var velocityY = 0f
        private set

    /** Whether the gesture so far scrolled or pinched. */
    val shaped get() = scrolling || pinching

    fun begin(x: Float, y: Float, time: Long) {
        flushSecondaryTap(time)
        reset()
        count = 1
        maxCount = 1
        this.x = x
        this.y = y
        startX = x
        startY = y
        started = time
        previousTime = time
        infinity.begin(x, y, time)
        velocityX = 0f
        velocityY = 0f
    }

    fun update(fingers: Int, x: Float, y: Float, span: Float, time: Long) {
        if (fingers != 1) infinity.cancel()
        if (fingers != count) {
            releaseDrag()
            count = fingers
            maxCount = max(maxCount, fingers)
            this.x = x
            this.y = y
            startX = x
            startY = y
            this.span = span
            startSpan = span
            previousTime = time
            if (fingers >= 3) {
                secondaryTap = -1
                moved = false
                scrolling = false
                pinching = false
                dictationGesture = false
                speed = -1f
                velocityX = 0f
                velocityY = 0f
            }
            if (fingers == 2 && maxCount == 2 && !dictationGesture && sink.isDictating()) {
                dictationGesture = true
                secondaryTap = -1
                sink.stopDictation()
            }
            return
        }
        if (dictationGesture) {
            if (!dictationResolved && fingers == 2 && maxCount == 2 &&
                abs(span - startSpan) > max(PINCH_DP, startSpan * DICTATION_PINCH_SHARE)
            ) {
                dictationResolved = true
                sink.finishDictation(if (span < startSpan) DictationFinish.ENTER else DictationFinish.DISCARD)
            }
            return
        }
        val dx = x - this.x
        val dy = y - this.y
        val distance = hypot(x - startX, y - startY)
        if ((count == 1 && maxCount == 1) || (count == 3 && maxCount == 3)) {
            if (distance > SLOP_DP) moved = true
            if (moved) {
                if (count == 3 && !dragging) {
                    dragging = true
                    sink.dragStart()
                }
                val now = hypot(dx, dy) / max(1L, time - previousTime)
                speed = if (speed < 0f) now else speed * 0.6f + now * 0.4f
                sink.pointer(dx, dy, gain(speed), speed, dragging)
            }
        } else if (count == 2 && maxCount == 2) {
            if (!scrolling && abs(span - startSpan) > PINCH_DP) {
                pinching = true
                moved = true
            }
            if (!pinching && distance > SLOP_DP) {
                scrolling = true
                moved = true
            }
            if (pinching && this.span > 0f) {
                sink.zoom(span / this.span)
            } else if (scrolling) {
                sink.scroll(dx, dy, momentum = false)
                val seconds = max(1L, time - previousTime) / 1000f
                velocityX = dx / seconds
                velocityY = dy / seconds
                lastScrollTime = time
            }
        } else if (count >= 3 && distance > SLOP_DP) {
            moved = true
        }
        this.x = x
        this.y = y
        this.span = span
        previousTime = time
        trace(x, y, time)
    }

    /** Historical touch samples feed recognition without replaying pointer/scroll output. */
    fun trace(x: Float, y: Float, time: Long) {
        if (count == 1 && maxCount == 1 && infinity.move(x, y, time)) {
            // A batched closed trace can finish at the touch-down position;
            // its small endpoint displacement must not turn it into a tap.
            moved = true
            sink.infinityDouble()
        }
    }

    /** The last finger lifted. Returns whether a two-finger scroll should coast on. */
    fun end(time: Long): Boolean {
        val recognizedInfinity = infinity.end(x, y, time)
        if (recognizedInfinity) moved = true
        val fling = scrolling && time - lastScrollTime < FLING_MS
        if (dictationGesture) {
            if (!dictationResolved && maxCount == 2) sink.finishDictation(DictationFinish.SEND)
        } else if (!moved && time - started < TAP_MS) {
            if (maxCount == 2) {
                if (secondaryTap >= 0 && time - secondaryTap < DOUBLE_MS) {
                    secondaryTap = -1
                    sink.dictate()
                } else {
                    flushSecondaryTap(time)
                    secondaryTap = time
                    secondaryX = x
                    secondaryY = y
                }
            } else if (maxCount == 1) {
                sink.click(x, y)
            }
        }
        if (recognizedInfinity) sink.infinityDouble()
        reset()
        return fling
    }

    fun cancel() {
        reset()
        secondaryTap = -1
        velocityX = 0f
        velocityY = 0f
    }

    /** A two-finger tap becomes a right-click once a second one can no longer follow it. */
    fun flushSecondaryTap(time: Long) {
        if (secondaryTap >= 0 && time - secondaryTap >= DOUBLE_MS) {
            secondaryTap = -1
            sink.secondaryClick(secondaryX, secondaryY)
        }
    }

    private fun releaseDrag() {
        if (!dragging) return
        dragging = false
        sink.dragEnd()
    }

    private fun reset() {
        releaseDrag()
        infinity.cancel()
        count = 0
        maxCount = 0
        moved = false
        pinching = false
        scrolling = false
        dictationGesture = false
        dictationResolved = false
        speed = -1f
    }

    companion object {
        const val DOUBLE_MS = 300L
        const val TAP_MS = 350L
        const val FLING_MS = 100L
        const val SLOP_DP = 5f
        const val PINCH_DP = 10f
        const val DICTATION_PINCH_SHARE = 0.2f

        /**
         * Pointer gain in px per dp of finger travel, from the finger's speed
         * in dp/ms: smooth acceleration from precision motion to fast sweeps.
         * A tuned approximation, not Apple's proprietary transfer function.
         * The pointer speed setting multiplies it.
         */
        fun gain(speed: Float): Float {
            val ramp = ((speed - 0.08f) / 1.42f).coerceIn(0f, 1f)
            return 0.25f + 2.75f * ramp * ramp * (3f - 2f * ramp)
        }
    }
}
