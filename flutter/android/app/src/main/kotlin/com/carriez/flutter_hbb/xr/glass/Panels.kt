package com.carriez.flutter_hbb.xr.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** A TextView in the design's type: [sizeDp] of the design, its weight and color. */
fun label(context: Context, unit: Float, sizeDp: Float, typeface: Typeface, color: Int, text: String = "") = TextView(context).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeDp * unit)
    this.typeface = typeface
    setTextColor(color)
    includeFontPadding = false
    this.text = text
}

private fun pill(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

private fun pillButton(context: Context, unit: Float, text: String, background: Int, color: Int, click: () -> Unit) =
    label(context, unit, 15f, Type.semibold, color, text).apply {
        gravity = Gravity.CENTER
        this.background = pill(background, 22f * unit)
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
    }

/**
 * The speed panel's contents, over the glass it pours out of: the pointer
 * speed, its slider, the gain curve with the last move on it, what the last
 * move did, and Reset and Done. Changes apply at once, so the pad can be
 * tried while it's open.
 */
@SuppressLint("ViewConstructor")
class SpeedPanel(
    context: Context,
    private val unit: Float,
    onSpeed: (Float) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) : LinearLayout(context) {
    private val value = label(context, unit, 17f, Type.semibold, Type.INK).apply { fontFeatureSettings = "tnum" }
    private val slider = GlassSlider(
        context, unit, "Pointer speed", min = 0.5f, max = 8f, step = 0.25f,
        ticks = (1..8).map(Int::toFloat), describe = { "%.2f×".format(it) }, onChange = onSpeed,
    )
    private val curve = GainCurve(context, unit)
    private val gain = label(context, unit, 13f, Typeface.DEFAULT, Type.SECONDARY).apply {
        setLineHeight((18f * unit).toInt())
    }

    init {
        orientation = VERTICAL
        fun u(v: Float) = (v * unit).toInt()
        setPadding(u(20f), u(18f), u(20f), u(16f))
        // Touches on the panel stay off the pad beneath it.
        isClickable = true
        contentDescription = "Pointer speed"

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(label(context, unit, 17f, Type.semibold, Type.INK, "Pointer speed"), LayoutParams(0, -2, 1f))
            addView(value, LayoutParams(-2, -2))
        }
        addView(header, LayoutParams(-1, -2))

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(IconView(context, Icon.SLOWER, unit), LayoutParams(u(14f), u(14f)))
            addView(slider, LayoutParams(0, u(44f), 1f).apply { setMargins(u(12f), 0, u(12f), 0) })
            addView(IconView(context, Icon.FASTER, unit), LayoutParams(u(22f), u(14f)))
        }
        addView(row, LayoutParams(-1, -2).apply { topMargin = u(4f) })
        addView(curve, LayoutParams(-1, u(12f + 96f + 4f + 16f + 8f + 2f)).apply { topMargin = u(8f) })
        addView(gain, LayoutParams(-1, -2).apply { setMargins(u(2f), u(10f), u(2f), 0) })
        addView(View(context), LayoutParams(-1, 0, 1f))

        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(pillButton(context, unit, "Reset to 3×", 0x0F000000, Type.INK, onReset), LayoutParams(0, u(44f), 1f).apply { rightMargin = u(4f) })
            addView(pillButton(context, unit, "Done", Type.INK, Color.WHITE, onDone), LayoutParams(0, u(44f), 1f).apply { leftMargin = u(4f) })
        }
        addView(buttons, LayoutParams(-1, -2))
    }

    /** Shows the setting and, once the pad has moved the pointer, the last move. */
    fun show(speed: Float, lastV: Float?) {
        value.text = "%.2f×".format(speed)
        slider.value = speed
        curve.speed = speed
        curve.lastV = lastV
        gain.text = if (lastV == null) {
            "Changes apply right away, so try the glass while this is open. Scrolling and pinch keep their own speed."
        } else {
            "Your last move: %d dp/s, so %.1f px per dp. Scrolling and pinch keep their own speed.".format(
                Math.round(lastV * 1000f), TrackpadGesture.gain(lastV) * speed,
            )
        }
    }
}

/** A setting that's on or off: its title and what it does beside a switch, the whole row a touch target. */
@SuppressLint("ViewConstructor")
class SwitchRow(
    context: Context,
    unit: Float,
    title: String,
    detail: String,
    onChange: (Boolean) -> Unit,
) : LinearLayout(context) {
    private val switch = GlassSwitch(context, unit)

    var checked: Boolean
        get() = switch.checked
        set(value) {
            switch.checked = value
        }

    init {
        fun u(v: Float) = (v * unit).toInt()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(u(4f), 0, u(4f), 0)
        isClickable = true
        isFocusable = true
        contentDescription = title
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(label(context, unit, 15f, Type.semibold, Type.INK, title), LayoutParams(-1, -2))
                addView(label(context, unit, 13f, Typeface.DEFAULT, Type.SECONDARY, detail), LayoutParams(-1, -2).apply { topMargin = u(2f) })
            },
            LayoutParams(0, -2, 1f),
        )
        addView(switch, LayoutParams(u(51f), u(31f)).apply { leftMargin = u(12f) })
        setOnClickListener {
            checked = !checked
            onChange(checked)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = checked
        info.stateDescription = if (checked) "On" else "Off"
    }
}

/** Everything the settings panel shows. */
data class XrSettings(
    val glasses: String? = null,
    val distance: Float = ScreenSettings.DISTANCE_ONE_TO_ONE,
    val curvature: Float = ScreenSettings.CURVATURE_FLAT,
    val motionPrediction: Boolean = false,
    val speed: Float = 3f,
    val anchored: Boolean = true,
    val fit: Boolean = true,
    val fullBuffer: Boolean = true,
    val panX: Float = 0.5f,
    val panY: Float = 0.5f,
    val tracking: String = "",
    val status: String = "",
    val info: String = "",
)

/** What the settings panel's controls do. */
interface SettingsActions {
    fun distance(value: Float)
    fun curvature(value: Float)
    fun motionPrediction(on: Boolean)
    fun speed(value: Float)
    fun anchored(on: Boolean)
    fun recenter()
    fun recheckCalibration()
    fun calibrateStationary()
    fun fit(on: Boolean)
    fun fullBuffer(on: Boolean)
    fun pan(x: Float, y: Float)
    fun clearText()
    fun wake()
    fun sessionMenu()
    fun exitXr()
}

/**
 * The settings panel's contents, over the glass it pours out of, after Mac XR's
 * menu bar panel (MenuPanel.swift) and its Dashboard. All of XR mode's settings
 * live here, so they scroll inside the glass: the glasses, the screen's distance
 * and curvature, head tracking and its calibration or, with that off, the flat
 * view, the pointer's speed, and the session.
 *
 * Changes apply as they're made, so there's nothing to confirm; the gear or a
 * touch outside closes it.
 */
@SuppressLint("ViewConstructor")
class SettingsPanel(
    context: Context,
    private val unit: Float,
    private val actions: SettingsActions,
) : ScrollView(context) {
    private val badge = GlassesBadge(context, unit)
    private val dot = StatusDot(context, unit)
    private val name = label(context, unit, 17f, Type.semibold, Type.INK).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val connection = label(context, unit, 13f, Typeface.DEFAULT, Type.SECONDARY)
    private val device = LinearLayout(context)
    private val help = detail(CONNECT_HELP)

    private val trackedOnly = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val flatOnly = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val panOnly = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val topView = ScreenTopView(context, unit)
    private val distanceValue = valueLabel()
    private val curvatureValue = valueLabel()
    private val distance = GlassSlider(
        context, unit, "Distance", ScreenSettings.DISTANCE_MIN, ScreenSettings.DISTANCE_MAX, step = 0.01f, nudge = 0.05f,
        ticks = listOf(ScreenSettings.DISTANCE_ONE_TO_ONE), caption = ScreenSettings.DISTANCE_ONE_TO_ONE to "1:1", insetThumb = true,
        snap = ScreenSettings::snapDistance, describe = ScreenSettings::distanceText, onChange = actions::distance,
    )
    private val curvature = GlassSlider(
        context, unit, "Curvature", ScreenSettings.CURVATURE_FLAT, ScreenSettings.CURVATURE_AROUND, step = 0.01f, nudge = 0.05f,
        insetThumb = true, describe = ScreenSettings::curvatureText, onChange = actions::curvature,
    )
    private val anchored = SwitchRow(context, unit, "Head tracking", "Keeps the screen fixed in the room (3DoF).", actions::anchored)
    private val tracking = detail("")
    private val motionPrediction = SwitchRow(context, unit, "Motion prediction", "Predicts ahead to offset display delay.", actions::motionPrediction)
    private val calibrationHelp = detail("Place the glasses on a stable surface and leave them still until calibration completes.")
    private val calibrationConfirm = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val calibrationStart = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val fit = SwitchRow(context, unit, "Fit desktop", "Off shows 1:1 pixels, moved with the sliders below.", actions::fit)
    private val fullBuffer = SwitchRow(context, unit, "Full-res buffer", "Renders at the desktop's size rather than the output's.", actions::fullBuffer)
    private val panX: GlassSlider = GlassSlider(
        context, unit, "Horizontal viewport", 0f, 1f, step = 0.01f, nudge = 0.05f, insetThumb = true,
        describe = { "%d%%".format((it * 100f).roundToInt()) }, onChange = { actions.pan(it, panY.value) },
    )
    private val panY: GlassSlider = GlassSlider(
        context, unit, "Vertical viewport", 0f, 1f, step = 0.01f, nudge = 0.05f, insetThumb = true,
        describe = { "%d%%".format((it * 100f).roundToInt()) }, onChange = { actions.pan(panX.value, it) },
    )

    private val speedValue = valueLabel()
    private val speed = GlassSlider(
        context, unit, "Pointer speed", min = 0.5f, max = 8f, step = 0.25f, ticks = (1..8).map(Int::toFloat),
        insetThumb = true, describe = { "%.2f×".format(it) }, onChange = actions::speed,
    )
    private val info = detail("")
    private val status = detail("")

    private fun valueLabel() = label(context, unit, 15f, Typeface.DEFAULT, Type.SECONDARY).apply { fontFeatureSettings = "tnum" }

    private fun detail(text: String) = label(context, unit, 13f, Typeface.DEFAULT, Type.SECONDARY, text).apply {
        setLineHeight((18f * unit).toInt())
    }

    private fun u(v: Float) = (v * unit).toInt()

    private fun LinearLayout.section(title: String) = addView(
        label(context, unit, 13f, Type.semibold, Type.SECONDARY, title),
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(u(4f), u(20f), u(4f), 0) },
    )

    private fun LinearLayout.row(view: View, top: Float = 8f) =
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(top) })

    private fun LinearLayout.padded(view: View, top: Float = 4f) =
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { setMargins(u(4f), u(top), u(4f), 0) })

    private fun buttons(vararg items: Pair<String, () -> Unit>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { i, (text, click) ->
            addView(
                pillButton(context, unit, text, 0x0F000000, Type.INK, click),
                LinearLayout.LayoutParams(0, u(44f), 1f).apply { if (i > 0) leftMargin = u(8f) },
            )
        }
    }

    init {
        // Touches on the panel stay off the pad beneath it.
        isClickable = true
        contentDescription = "Settings"
        isVerticalScrollBarEnabled = true
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(u(24f))
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(20f), u(16f), u(20f), u(20f))
        }
        addView(content, LayoutParams(-1, -2))

        device.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(10f), u(10f), u(14f), u(10f))
            background = GradientDrawable().apply {
                setColor(0x8CFFFFFF.toInt())
                cornerRadius = 22f * unit
                setStroke(u(1f).coerceAtLeast(1), 0x0F000000)
            }
            // Read as one, and again when the glasses come or go.
            isFocusable = true
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            addView(badge, LinearLayout.LayoutParams(u(44f), u(44f)))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(name, LinearLayout.LayoutParams(-1, -2))
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(dot, LinearLayout.LayoutParams(u(8f), u(8f)).apply { rightMargin = u(6f) })
                            addView(connection, LinearLayout.LayoutParams(0, -2, 1f))
                        },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(3f) },
                    )
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(12f) },
            )
        }
        content.addView(device, LinearLayout.LayoutParams(-1, -2))
        content.padded(help, 10f)

        content.section("View")
        content.row(anchored)
        content.addView(trackedOnly, LinearLayout.LayoutParams(-1, -2))
        content.addView(flatOnly, LinearLayout.LayoutParams(-1, -2))

        trackedOnly.padded(tracking, 8f)
        trackedOnly.row(motionPrediction, 12f)
        trackedOnly.row(buttons("Recenter" to actions::recenter, "Recheck" to actions::recheckCalibration), 12f)
        calibrationStart.row(buttons("Desk calibration" to { showCalibrationConfirm(true) }))
        calibrationConfirm.padded(calibrationHelp, 10f)
        calibrationConfirm.row(
            buttons(
                "Cancel" to { showCalibrationConfirm(false) },
                "Start" to {
                    showCalibrationConfirm(false)
                    actions.calibrateStationary()
                },
            ),
        )
        calibrationConfirm.visibility = GONE
        trackedOnly.addView(calibrationStart, LinearLayout.LayoutParams(-1, -2))
        trackedOnly.addView(calibrationConfirm, LinearLayout.LayoutParams(-1, -2))

        trackedOnly.section("Screen")
        trackedOnly.addView(topView, LinearLayout.LayoutParams(-1, u(112f)).apply { topMargin = u(6f) })
        trackedOnly.row(header("Distance", distanceValue), 12f)
        trackedOnly.addView(sliderRow(Icon.NEAR, distance, Icon.FAR), LinearLayout.LayoutParams(-1, u(44f + GlassSlider.CAPTION_DP)))
        trackedOnly.row(header("Curvature", curvatureValue), 6f)
        trackedOnly.addView(sliderRow(Icon.FLAT, curvature, Icon.CURVED), LinearLayout.LayoutParams(-1, u(44f)))

        flatOnly.section("Flat view")
        flatOnly.row(fit)
        flatOnly.row(fullBuffer, 12f)
        flatOnly.addView(panOnly, LinearLayout.LayoutParams(-1, -2))
        panOnly.row(header("Horizontal viewport", null), 12f)
        panOnly.addView(panX, LinearLayout.LayoutParams(-1, u(44f)))
        panOnly.row(header("Vertical viewport", null), 6f)
        panOnly.addView(panY, LinearLayout.LayoutParams(-1, u(44f)))

        content.section("Pointer")
        content.row(header("Pointer speed", speedValue))
        content.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(IconView(context, Icon.SLOWER, unit), LinearLayout.LayoutParams(u(14f), u(14f)))
                addView(speed, LinearLayout.LayoutParams(0, u(44f), 1f).apply { setMargins(u(12f), 0, u(12f), 0) })
                addView(IconView(context, Icon.FASTER, unit), LinearLayout.LayoutParams(u(22f), u(14f)))
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        content.section("Session")
        content.padded(info, 6f)
        content.padded(status, 6f)
        content.row(buttons("Wake" to actions::wake, "Clear text" to actions::clearText), 10f)
        content.row(buttons("Session menu" to actions::sessionMenu))
        content.addView(
            pillButton(context, unit, "Exit XR", Type.INK, Color.WHITE, actions::exitXr),
            LinearLayout.LayoutParams(-1, u(44f)).apply { topMargin = u(8f) },
        )
    }

    private fun showCalibrationConfirm(on: Boolean) {
        calibrationConfirm.visibility = if (on) VISIBLE else GONE
        calibrationStart.visibility = if (on) GONE else VISIBLE
    }

    private fun header(title: String, value: TextView?) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(u(4f), 0, u(4f), 0)
        addView(label(context, unit, 15f, Type.semibold, Type.INK, title), LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) addView(value, LinearLayout.LayoutParams(-2, -2))
    }

    /** The slider between its end icons, which line up with its track rather than its caption. */
    private fun sliderRow(start: Icon, slider: GlassSlider, end: Icon) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        fun icon(which: Icon) = IconView(context, which, unit)
        fun iconLayout() = LinearLayout.LayoutParams(u(20f), u(15f)).apply { topMargin = u(14.5f) }
        addView(icon(start), iconLayout())
        addView(slider, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(u(4f), 0, u(4f), 0) })
        addView(icon(end), iconLayout())
    }

    private fun GlassSlider.show(next: Float) {
        if (!grabbed) value = next
    }

    fun show(s: XrSettings) {
        val connected = s.glasses != null
        badge.connected = connected
        dot.connected = connected
        name.text = s.glasses ?: "No glasses"
        connection.text = if (connected) "Connected" else "Not connected"
        device.contentDescription = if (connected) "${s.glasses}, connected" else "No glasses connected"
        help.visibility = if (connected) GONE else VISIBLE

        anchored.checked = s.anchored
        trackedOnly.visibility = if (s.anchored) VISIBLE else GONE
        flatOnly.visibility = if (s.anchored) GONE else VISIBLE
        tracking.text = s.tracking
        tracking.visibility = if (s.tracking.isEmpty()) GONE else VISIBLE
        motionPrediction.checked = s.motionPrediction
        topView.show(s.distance, s.curvature)
        distance.show(s.distance)
        distanceValue.text = ScreenSettings.distanceText(s.distance)
        curvature.show(s.curvature)
        curvatureValue.text = ScreenSettings.curvatureText(s.curvature)

        fit.checked = s.fit
        fullBuffer.checked = s.fullBuffer && s.fit
        panOnly.visibility = if (s.fit) GONE else VISIBLE
        panX.show(s.panX)
        panY.show(s.panY)

        speed.show(s.speed)
        speedValue.text = "%.2f×".format(s.speed)
        info.text = s.info
        info.visibility = if (s.info.isEmpty()) GONE else VISIBLE
        status.text = s.status
        status.visibility = if (s.status.isEmpty()) GONE else VISIBLE
    }

    private companion object {
        const val CONNECT_HELP = "Plug the glasses into this phone’s USB-C port. In the glasses, choose Follow mode and turn Stabilizer off."
    }
}

/**
 * The text field's contents, over its glass: type, or dictate and watch the
 * words arrive. While dictating it grows, the words show above the controls
 * with the one still being heard in grey, and the controls say it's
 * listening.
 *
 * Typing goes straight to the remote computer: [onText] gets the field's text as
 * it changes, and the IME's action and Backspace on an empty field, which leave
 * the text as it was, arrive through [onKey].
 *
 * At rest the keyboard icon, the hint and the mic are drawn with the glass
 * (UiPass), so they move with it as the keyboard comes and goes: a view
 * follows the glass a frame or two late, and can't squash with it. Here
 * they're only touch targets, and the typed text.
 */
@SuppressLint("ViewConstructor")
class TextField(
    context: Context,
    private val unit: Float,
    onMic: () -> Unit,
    onClear: () -> Unit,
    private val onKeyboard: () -> Unit,
    onText: (String) -> Unit,
    private val onKey: (String) -> Unit,
) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val caret = Caret(unit)
    private val transcript = label(context, unit, 17f, Type.medium, Type.INK).apply {
        setLineHeight((24f * unit).toInt())
        alpha = 0f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    // Taps ask for the keyboard, as the remote's keyboard gate may have to say no first.
    val edit = RemoteEditText(context) { onKey(KEY_BACKSPACE) }.apply {
        background = null
        hint = "Type or dictate"
        setHintTextColor(Color.TRANSPARENT)
        setTextColor(Type.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f * unit)
        setPadding((4f * unit).toInt(), 0, (4f * unit).toInt(), 0)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        contentDescription = "Text for the remote computer"
        isFocusableInTouchMode = false
    }
    private val keyboard = IconButton(context, Icon.KEYBOARD, 24f, unit, "Show keyboard")
    private val clear = IconButton(context, Icon.CLEAR, 20f, unit, "Clear text on both screens")
    private val listeningHint = label(context, unit, 13f, Type.medium, Type.SECONDARY, "Live in the glasses").apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val listening = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        addView(LevelDots(context, unit), LinearLayout.LayoutParams((42f * unit).toInt(), (12f * unit).toInt()).apply { leftMargin = (14f * unit).toInt() })
        addView(label(context, unit, 15f, Type.semibold, Type.INK, "Listening"), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (8f * unit).toInt() })
        addView(listeningHint, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = (8f * unit).toInt() })
    }
    private val mic = IconButton(context, Icon.MIC, 20f, unit, "Start dictation", Color.WHITE, Type.INK)
    // The controls' row hangs at the bottom; while dictating, the field's
    // squircle corners are as big as the pad's, so the row moves in clear of them.
    private val rowLayout = LayoutParams(-1, (48f * unit).toInt(), Gravity.BOTTOM).apply { inset(6f) }

    private fun LayoutParams.inset(dp: Float) {
        val px = (dp * unit).toInt()
        setMargins(px, 0, px, px)
    }
    private val blink = object : Runnable {
        override fun run() {
            caret.on = !caret.on
            transcript.invalidate()
            handler.postDelayed(this, 500)
        }
    }

    init {
        fun u(v: Float) = (v * unit).toInt()
        isClickable = true
        // Clear of the squircle's top corners.
        addView(transcript, LayoutParams(-1, -2).apply { setMargins(u(26f), u(18f), u(26f), 0) })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(keyboard, LinearLayout.LayoutParams(u(48f), u(48f)))
            addView(edit, LinearLayout.LayoutParams(0, u(48f), 1f))
            addView(clear, LinearLayout.LayoutParams(u(44f), u(44f)))
            addView(listening, LinearLayout.LayoutParams(0, u(48f), 1f))
            addView(mic, LinearLayout.LayoutParams(u(48f), u(48f)).apply { leftMargin = u(2f) })
        }
        addView(row, rowLayout)
        clear.visibility = GONE
        keyboard.hidden = true
        mic.hidden = true
        keyboard.setOnClickListener { onKeyboard() }
        edit.setOnClickListener { if (!edit.hasFocus()) onKeyboard() }
        clear.setOnClickListener { onClear() }
        mic.setOnClickListener { onMic() }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty() || listening.visibility == VISIBLE) GONE else VISIBLE
                if (!resetting) onText(s?.toString() ?: "")
            }
        })
        edit.setOnEditorActionListener { _, action, event ->
            val enter = action == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) onKey(KEY_ENTER)
            enter
        }
    }

    private var resetting = false

    val text: String get() = edit.text.toString()

    /** Empties the field without that counting as deleting what the remote already has. */
    fun clearLocally() {
        resetting = true
        edit.setText("")
        resetting = false
    }

    fun showKeyboard() {
        edit.isFocusableInTouchMode = true
        edit.requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        edit.clearFocus()
        edit.isFocusableInTouchMode = false
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(edit.windowToken, 0)
    }

    /** Switches between typing and dictating. */
    fun dictating(on: Boolean) {
        listening.visibility = if (on) VISIBLE else GONE
        keyboard.visibility = if (on) GONE else VISIBLE
        edit.visibility = if (on) GONE else VISIBLE
        clear.visibility = if (on || text.isEmpty()) GONE else VISIBLE
        rowLayout.inset(if (on) 14f else 6f)
        requestLayout()
        mic.contentDescription = if (on) "Stop dictation" else "Start dictation"
        // Dictating, the field's views draw its controls again.
        mic.hidden = !on
        mic.instead = if (on) { canvas, cx, cy -> stopSquare(canvas, cx, cy) } else null
        mic.invalidate()
        transcript.animate().cancel()
        transcript.animate().alpha(if (on) 1f else 0f).setDuration(250).setStartDelay(if (on) 250 else 0).start()
        handler.removeCallbacks(blink)
        if (on) {
            hideKeyboard()
            handler.post(blink)
        }
    }

    /** Once the next two-finger touch has stopped recognition, says what its fingers can do now. */
    fun dictationStopping(stopping: Boolean) {
        listeningHint.text = if (stopping) "Lift: send · In: Enter · Out: discard" else "Live in the glasses"
    }

    /** Whether the dictating controls are up, so the first words have somewhere to go. */
    val isHearing get() = listening.visibility == VISIBLE && transcript.text.isNotEmpty()

    /** What's been heard so far: [final] words, and the [partial] one still being heard. */
    fun heard(final: String, partial: String) {
        val words = SpannableStringBuilder(final)
        if (partial.isNotEmpty()) {
            if (words.isNotEmpty()) words.append(' ')
            val from = words.length
            words.append(partial)
            words.setSpan(ForegroundColorSpan(0xFF5E5E64.toInt()), from, words.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val at = words.length
        words.append('​')
        words.setSpan(caret, at, words.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        transcript.text = words
    }

    private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private fun stopSquare(canvas: Canvas, cx: Float, cy: Float) {
        val h = 7f * unit
        canvas.drawRoundRect(cx - h, cy - h, cx + h, cy + h, 3f * unit, 3f * unit, stopPaint)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    companion object {
        const val KEY_ENTER = "VK_RETURN"
        const val KEY_BACKSPACE = "VK_BACK"
    }

    /** The design's caret: a 2 × 19 dp bar, blinking once a second. */
    private class Caret(private val unit: Float) : ReplacementSpan() {
        var on = true
        private val paint = Paint().apply { color = Type.INK }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = (4f * unit).toInt()

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            if (!on) return
            val left = x + 2f * unit
            canvas.drawRect(left, y + 3f * unit - 19f * unit, left + 2f * unit, y + 3f * unit, this.paint)
        }
    }
}

/** An EditText that reports Backspace on an empty field, which an editor would otherwise swallow. */
@SuppressLint("ViewConstructor")
class RemoteEditText(context: Context, private val onEmptyBackspace: () -> Unit) : EditText(context) {
    override fun onCreateInputConnection(attrs: EditorInfo): InputConnection? {
        val inner = super.onCreateInputConnection(attrs) ?: return null
        return object : InputConnectionWrapper(inner, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (text.isNullOrEmpty() && beforeLength > 0) {
                    onEmptyBackspace()
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (text.isNullOrEmpty() && event.keyCode == KeyEvent.KEYCODE_DEL) {
                    if (event.action == KeyEvent.ACTION_DOWN) onEmptyBackspace()
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}
