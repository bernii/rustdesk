package com.carriez.flutter_hbb.xr.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * The screen in the glasses, as Mac XR's panel sets it (UIModel.swift): how
 * far away it is, from 0.5× to 3×, snapping to 1×, where one desktop pixel
 * maps to one glasses pixel at the center; and how far it curves, from flat
 * to a cylinder around the wearer's head.
 */
object ScreenSettings {
    const val DISTANCE_MIN = 0.5f
    const val DISTANCE_MAX = 3f
    const val DISTANCE_ONE_TO_ONE = 1f
    const val CURVATURE_FLAT = 0f
    const val CURVATURE_AROUND = 1f
    private const val DISTANCE_SNAP = 0.04f

    /** XrProjectionRenderer's field of view, which sets how wide the screen is drawn. */
    private const val FOV_DEGREES = 45.0

    /** How far the wedge of the view spreads per 1× of distance, each side of center. */
    val HALF_VIEW = tan(Math.toRadians(FOV_DEGREES / 2.0)).toFloat()

    /** The screen's width in units of 1× away: the plane at 1:1 is 4·tan(fov/2) wide. */
    val WIDTH = 4f * HALF_VIEW

    fun snapDistance(value: Float) = if (abs(value - DISTANCE_ONE_TO_ONE) < DISTANCE_SNAP) DISTANCE_ONE_TO_ONE else value

    fun distanceText(value: Float) =
        "%.2f×".format(value) + if (abs(value - DISTANCE_ONE_TO_ONE) < 0.0001f) " · 1:1 pixels" else ""

    fun curvatureText(value: Float): String {
        val percent = (value * 100f).roundToInt()
        return when {
            percent <= 0 -> "Flat"
            percent >= 100 -> "100% · around you"
            else -> "$percent%"
        }
    }

    fun describe(distance: Float, curvature: Float): String {
        val percent = (curvature * 100f).roundToInt()
        return "Top view: screen %.2f times away, %s".format(distance, if (percent <= 0) "flat" else "$percent% curved")
    }

    /**
     * The screen seen from above, as x, y pairs in units of 1×, the wearer at
     * the origin facing +x. Curvature bends it into a cylinder of radius
     * distance/curvature, keeping its width along the arc.
     */
    fun arc(distance: Float, curvature: Float): FloatArray {
        if (curvature < 0.005f) return floatArrayOf(distance, -WIDTH / 2f, distance, WIDTH / 2f)
        val radius = distance / curvature
        val angle = WIDTH / radius
        return FloatArray((ARC_STEPS + 1) * 2) { i ->
            val p = -angle / 2f + angle * (i / 2) / ARC_STEPS
            if (i % 2 == 0) distance - radius + radius * cos(p) else radius * sin(p)
        }
    }

    private const val ARC_STEPS = 48
}

/** The settings panel's settings, saved as they change, so there's nothing to confirm and they're there at the next launch. */
class SavedSettings(context: Context) {
    private val prefs = context.getSharedPreferences("xr-glass-settings", Context.MODE_PRIVATE)

    var distance: Float
        get() = prefs.getFloat(DISTANCE, ScreenSettings.DISTANCE_ONE_TO_ONE).coerceIn(ScreenSettings.DISTANCE_MIN, ScreenSettings.DISTANCE_MAX)
        set(value) = prefs.edit().putFloat(DISTANCE, value).apply()

    var curvature: Float
        get() = prefs.getFloat(CURVATURE, ScreenSettings.CURVATURE_FLAT).coerceIn(ScreenSettings.CURVATURE_FLAT, ScreenSettings.CURVATURE_AROUND)
        set(value) = prefs.edit().putFloat(CURVATURE, value).apply()

    var motionPrediction: Boolean
        get() = prefs.getBoolean(MOTION_PREDICTION, false)
        set(value) = prefs.edit().putBoolean(MOTION_PREDICTION, value).apply()

    private companion object {
        const val DISTANCE = "distance"
        const val CURVATURE = "curvature"
        const val MOTION_PREDICTION = "motionPrediction"
    }
}

/** A USB device, as far as telling glasses from others goes. */
class UsbInfo(val vendorId: Int, val productName: String?)

/**
 * The name of the XREAL glasses plugged in, or null if there are none: their
 * display, known by its name as Mac XR knows it, or else their USB device,
 * known by its vendor as rustdesk-xr-probe knows it. Other external displays
 * don't count.
 */
fun glassesName(displays: List<String>, usb: List<UsbInfo>): String? {
    displays.firstOrNull { it.contains("xreal", ignoreCase = true) || it.contains("nreal", ignoreCase = true) }?.let { return it.trim() }
    val device = usb.firstOrNull { it.vendorId == XREAL_VENDOR_ID } ?: return null
    return device.productName?.trim()?.takeIf(String::isNotEmpty) ?: "XREAL glasses"
}

private const val XREAL_VENDOR_ID = 0x3318

/**
 * Tells [onChange] the name of the glasses plugged into this phone, or null,
 * on the main thread: at [start] and whenever displays or USB devices come and
 * go. Neither list needs a permission.
 */
class GlassesWatcher(private val context: Context, private val onChange: (String?) -> Unit) : DisplayManager.DisplayListener {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val usb: UsbManager? = context.getSystemService(UsbManager::class.java)
    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }
    private var last: String? = null
    private var told = false

    fun start() {
        displays.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // A system broadcast, which still reaches a receiver that isn't exported.
        context.registerReceiver(usbEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun stop() {
        displays.unregisterDisplayListener(this)
        context.unregisterReceiver(usbEvents)
    }

    private fun refresh() {
        val devices = usb?.deviceList?.values.orEmpty().map { UsbInfo(it.vendorId, it.productName) }
        val name = glassesName(displays.displays.map { it.name }, devices)
        if (told && name == last) return
        told = true
        last = name
        onChange(name)
    }

    override fun onDisplayAdded(displayId: Int) = refresh()

    override fun onDisplayRemoved(displayId: Int) = refresh()

    override fun onDisplayChanged(displayId: Int) = refresh()
}
