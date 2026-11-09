package com.carriez.flutter_hbb.xr.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowInsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * The whole screen's glass: the marble, the oil that is the pad, and the
 * controls' droplets (the views over it handle their touches). Every touch
 * it gets goes to the oil, so a finger near the pad's edge pulls it; touches
 * that start on the pad are also trackpad gestures, for [sink].
 *
 * It runs at the display's highest rate while touched or while anything
 * animates. Once everything settles it drops to 60 Hz, as the moving marble
 * and a light that follows the phone's turn never stop, or stops drawing
 * until the next change if neither is on.
 */
@SuppressLint("ViewConstructor")
class GlassView(
    context: Context,
    params: TouchGlassParams,
    private val scene: Scene,
    private val sink: TrackpadGesture.Sink,
    private val onFailed: (String) -> Unit,
    private val onFrame: (Scene.Frame) -> Unit,
) : GLSurfaceView(context) {

    private val fingers = Fingers()
    private val deviceMotion = DeviceMotion(context)
    private val renderer: GlassRenderer
    private val gesture = TrackpadGesture(sink)
    private val density = resources.displayMetrics.density
    // Read on the sensor thread too, to wake only once.
    @Volatile
    private var idle = false
    private var onPad = false

    // The latest frame from the GL thread, handed to the UI thread once per post.
    @Volatile
    private var latest: Scene.Frame? = null
    private val framePending = AtomicBoolean(false)
    private val deliverFrame = Runnable {
        framePending.set(false)
        latest?.let {
            pad = it.pad
            onFrame(it)
        }
    }

    /** Where the pad is, as last drawn; null before the first frame. */
    var pad: Box? = null
        private set

    private var velocityX = 0f
    private var velocityY = 0f
    private var momentumTime = 0L

    // Whoever's wearing the glasses isn't watching this screen, and everything drawn
    // here is GPU the head-tracked view can't have. So a while after the last touch
    // or change it all stops, marble and light too, until the next.
    private var freezeState = false
    private var lastWake = 0L
    private val freeze = object : Runnable {
        override fun run() {
            val quiet = SystemClock.uptimeMillis() - lastWake
            if (quiet < FREEZE_MS || !fingers.isIdle()) {
                scheduleFreeze(maxOf(FREEZE_MS - quiet, 250L))
                return
            }
            freezeState = true
            idle = true
            deviceMotion.stop()
            renderMode = RENDERMODE_WHEN_DIRTY
        }
    }
    private val secondaryTap = Runnable { gesture.flushSecondaryTap(SystemClock.uptimeMillis()) }
    private val momentum = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (sink.isDictating()) {
                sink.settle()
                return
            }
            val seconds = min(0.05f, (now - momentumTime) / 1000f)
            momentumTime = now
            sink.scroll(velocityX * seconds, velocityY * seconds, momentum = true)
            val decay = exp(-6f * seconds)
            velocityX *= decay
            velocityY *= decay
            if (hypot(velocityX, velocityY) > 15f) postOnAnimation(this) else sink.settle()
        }
    }

    init {
        setEGLContextClientVersion(3)
        // Under load the glasses' head-tracked view goes first.
        setEGLContextFactory(com.carriez.flutter_hbb.xr.XrGlPriority(3, com.carriez.flutter_hbb.xr.XrGlPriority.LOW))
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        val listener = object : GlassRenderer.Listener {
            override fun onStats(text: String) = Unit

            override fun onSettled() {
                post {
                    if (!fingers.isIdle()) return@post
                    idle = true
                    if (renderer.animated) setFrameRate(IDLE_HZ) else renderMode = RENDERMODE_WHEN_DIRTY
                }
            }

            override fun onFrame(frame: Scene.Frame) {
                latest = frame
                if (framePending.compareAndSet(false, true)) post(deliverFrame)
            }
        }
        renderer = GlassRenderer(context.assets, params, density, fingers, deviceMotion, scene, listener)
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            renderer.effectsFile = java.io.File(context.filesDir, "glass-effects")
        }
        // Above the FlutterView's own surface, below the views over the glass.
        setZOrderMediaOverlay(true)
        setRenderer(Guarded(renderer) { message -> post { onFailed(message) } })
        renderMode = RENDERMODE_CONTINUOUSLY
        // The phone moving the oil (the G-force) wakes it as a touch does.
        if (params.gforce.enabled) deviceMotion.onMoved = { if (idle) post { wake() } }
        contentDescription = "Trackpad surface. Move one finger to move the pointer and tap to click. " +
            "Use two fingers to scroll or to right-click, and three fingers to drag. " +
            "Double-tap with two fingers to dictate. " +
            "Trace an infinity sign twice continuously within four seconds to show settings."
    }

    /** A touch-down no longer leads anywhere, such as when input is taken away: any held button is released. */
    fun cancelGestures() = release()

    /** Changes the scene on the GL thread, before its next frame, and wakes the frame rate up. */
    fun change(block: (Scene) -> Unit) {
        queueEvent { block(scene) }
        wake()
    }

    private fun wake() {
        lastWake = SystemClock.uptimeMillis()
        if (freezeState) {
            freezeState = false
            deviceMotion.start()
            scheduleFreeze()
        }
        if (!idle) {
            if (renderMode == RENDERMODE_WHEN_DIRTY) requestRender()
            return
        }
        idle = false
        setFrameRate(TOUCH_HZ)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** Whether nothing has been drawn since the glass went untouched. */
    val frozen get() = freezeState

    private fun scheduleFreeze(delay: Long = FREEZE_MS) {
        removeCallbacks(freeze)
        postDelayed(freeze, delay)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Deliver touches as they arrive rather than batched to the next frame.
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(e)
        fingers.onTouchEvent(e)
        wake()
        if (action == MotionEvent.ACTION_DOWN) onPad = pad?.let { e.x in it.x..it.right && e.y in it.y..it.bottom } ?: false
        if (onPad) track(e)
        return true
    }

    /** The trackpad's gestures, from the fingers' centroid and spread in dp, as the probe's TouchpadView feeds them. */
    private fun track(e: MotionEvent) {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) {
            release()
            return
        }
        if (e.pointerCount == 1 && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
            for (h in 0 until e.historySize) {
                gesture.trace(e.getHistoricalX(0, h) / density, e.getHistoricalY(0, h) / density, e.getHistoricalEventTime(h))
            }
        }
        val skip = if (action == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        val count = e.pointerCount - if (skip >= 0) 1 else 0
        var x = 0f
        var y = 0f
        for (i in 0 until e.pointerCount) {
            if (i != skip) {
                x += e.getX(i)
                y += e.getY(i)
            }
        }
        x /= count * density
        y /= count * density
        val span = if (count == 2) {
            val (a, b) = (0 until e.pointerCount).filter { it != skip }
            hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b)) / density
        } else {
            0f
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(momentum)
                gesture.begin(x, y, e.eventTime)
            }
            MotionEvent.ACTION_UP -> {
                // Include the lift position, which need not have appeared in a MOVE.
                gesture.update(count, x, y, span, e.eventTime)
                val shaped = gesture.shaped
                if (gesture.end(e.eventTime)) {
                    velocityX = gesture.velocityX.coerceIn(-MAX_FLING, MAX_FLING)
                    velocityY = gesture.velocityY.coerceIn(-MAX_FLING, MAX_FLING)
                    momentumTime = SystemClock.uptimeMillis()
                    postOnAnimation(momentum)
                } else if (shaped) {
                    sink.settle()
                }
                removeCallbacks(secondaryTap)
                postDelayed(secondaryTap, TrackpadGesture.DOUBLE_MS)
            }
            else -> gesture.update(count, x, y, span, e.eventTime)
        }
    }

    private fun release() {
        removeCallbacks(momentum)
        removeCallbacks(secondaryTap)
        gesture.cancel()
    }

    // The edge magnet needs touches at the oil's side edges, which lie inside
    // the system's back-gesture zones: there the system takes the touch and
    // the app never sees it. Android honours 200 dp of exclusion per side, so
    // the middle 200 dp of each side are the app's.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val band = (EXCLUSION_HEIGHT_DP * density).toInt()
        val depth = (EXCLUSION_DEPTH_DP * density).toInt()
        val from = (bottom - top - band) / 2
        systemGestureExclusionRects = listOf(Rect(0, from, depth, from + band), Rect(w - depth, from, w, from + band))
    }

    // The edge magnet's wall follows the display's own rounded corners.
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.let { corner ->
                queueEvent { renderer.screenCorner = corner.radius.toFloat() }
            }
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        setFrameRate(TOUCH_HZ)
    }

    override fun onResume() {
        super.onResume()
        freezeState = false
        deviceMotion.start()
        wake()
        scheduleFreeze()
    }

    override fun onPause() {
        removeCallbacks(freeze)
        release()
        deviceMotion.stop()
        super.onPause()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(freeze)
        release()
        super.onDetachedFromWindow()
    }

    private fun setFrameRate(hz: Float) {
        holder.surface.takeIf { it.isValid }?.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    /** A GL failure on this thread would take the remote session down with it; report it instead and stop drawing. */
    private class Guarded(private val inner: GLSurfaceView.Renderer, private val failed: (String) -> Unit) : GLSurfaceView.Renderer {
        private var dead = false

        private inline fun guard(block: () -> Unit) {
            if (dead) return
            try {
                block()
            } catch (failure: RuntimeException) {
                dead = true
                android.util.Log.e("RustDeskXR", "Glass trackpad failed", failure)
                failed("The XR trackpad could not start. Returned to the phone screen.")
            }
        }

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) =
            guard { inner.onSurfaceCreated(gl, config) }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) =
            guard { inner.onSurfaceChanged(gl, width, height) }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) = guard { inner.onDrawFrame(gl) }
    }

    private companion object {
        // Pixel 9's top rate; and half of it for the marble moving on its own.
        const val TOUCH_HZ = 120f
        const val IDLE_HZ = 60f
        const val FREEZE_MS = 8000L
        const val EXCLUSION_HEIGHT_DP = 200f
        const val EXCLUSION_DEPTH_DP = 64f
        // The fastest a scroll may coast, dp/s.
        const val MAX_FLING = 2500f
    }
}
