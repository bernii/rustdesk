package com.carriez.flutter_hbb.xr.glass

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout

/**
 * Puts the glass trackpad over the FlutterView in the phone's activity. It's a
 * sibling of the FlutterView rather than a Flutter platform view so touches reach
 * it unbuffered, its own keyboard works, and 120 Hz doesn't tie Flutter's threads
 * to it. The Flutter session keeps running underneath, and Dart hides this while
 * one of its dialogs or menus needs the screen.
 *
 * API 30 and up only: XrController doesn't create it, or start XR, on anything older.
 */
class XrGlassHost(
    private val activity: Activity,
    private val events: Events,
    speed: Float,
) : Trackpad.Host {
    /** Implemented by XrController. */
    interface Events {
        fun action(args: MutableMap<String, Any>)
        fun failed(message: String)
        fun screen(distance: Float, curvature: Float, prediction: Boolean)
    }

    private val density = activity.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val root = FrameLayout(activity)
    private val trackpad = Trackpad(activity, TouchGlassParams(), this, speed)
    private val orientation = activity.requestedOrientation
    private var running = false

    init {
        root.addView(trackpad, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            if (view.width > 0) {
                trackpad.screenChanged(Screen(view.width, view.height, density, bars.top, bars.bottom, ime.bottom))
            }
            insets
        }
        // The first insets can arrive before the first layout gives the size.
        root.addOnLayoutChangeListener { view, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) view.rootWindowInsets?.let { view.dispatchApplyWindowInsets(it) }
        }
        root.visibility = View.GONE
        activity.addContentView(root, FrameLayout.LayoutParams(-1, -1))
    }

    fun show(visible: Boolean) {
        if (visible == running) return
        running = visible
        if (visible) {
            // The design is laid out on a portrait artboard.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            root.visibility = View.VISIBLE
            trackpad.onResume()
        } else {
            trackpad.onPause()
            root.visibility = View.GONE
            activity.requestedOrientation = orientation
        }
    }

    fun close() {
        show(false)
        (root.parent as? android.view.ViewGroup)?.removeView(root)
    }

    fun update(values: Map<String, Any?>) = trackpad.update(values)

    /** Dart has passed the keyboard's gate and cleared the window's IME block; let that reach the window first. */
    fun showKeyboard() {
        handler.postDelayed({ trackpad.showKeyboard() }, IME_FLAG_SETTLE_MS)
    }

    /** The back key: true if it closed something here, so the session stays. */
    fun back() = trackpad.back()

    override fun action(name: String, values: Map<String, Any>) {
        val args = HashMap<String, Any>(values)
        args["action"] = name
        events.action(args)
    }

    override fun failed(message: String) = events.failed(message)

    override fun screenChanged(distance: Float, curvature: Float, prediction: Boolean) =
        events.screen(distance, curvature, prediction)

    private companion object {
        const val IME_FLAG_SETTLE_MS = 60L
    }
}
