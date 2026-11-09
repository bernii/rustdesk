package com.carriez.flutter_hbb.xr.glass

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The Trackpad design's behaviour, on the UI thread: the lock and its two-second
 * hold to unlock, the pointer speed and settings panels, typing and dictation, the
 * top row's buttons and the chip that says what each gesture did.
 *
 * The glass is drawn on the GL thread from [Scene], which this changes through
 * [GlassView.change]; the views over the glass, the buttons, the panel's and the
 * text field's contents, follow it as it draws.
 *
 * Everything it does to the session goes to [Host]; the session's state comes back
 * through [update]. The lock and the speed are decided here and only told to the
 * session, so a late echo can't undo what a finger just did.
 */
@SuppressLint("ViewConstructor")
class Trackpad(
    context: Context,
    params: TouchGlassParams,
    private val host: Host,
    private var speed: Float,
) : FrameLayout(context), TrackpadGesture.Sink, SettingsActions {

    interface Host {
        fun action(name: String, values: Map<String, Any> = emptyMap())
        fun failed(message: String)
        fun screenChanged(distance: Float, curvature: Float, prediction: Boolean)
    }

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    private val scene = Scene(density, reducedMotion)
    val glass = GlassView(context, params, scene, this, host::failed, ::follow)
    private val output = InputCoalescer(glass::postOnAnimation) { name, values -> host.action(name, values) }

    // The design's unit, px per dp of its 412-wide artboard, once laid out.
    private var unit = density
    private var screen: Screen? = null
    private var dismissing = false
    private val hit = android.graphics.Rect()
    private var geometry: Geometry? = null

    // XR starts fully locked: the whole screen is the pad, with only the lock on it.
    // Unlocking brings out the gear, or the infinity gesture does while locked.
    private var locked = true
    private var speedOpen = false
    private var dictating = false
    private var settingsShown = false
    private var settingsOpen = false
    private val saved = SavedSettings(context)
    private var glassesName: String? = null
    private val glassesWatcher = GlassesWatcher(context) {
        glassesName = it
        showSettings()
    }
    private val doubleTap = ViewConfiguration.get(context).let {
        DoubleTapDismiss(it.scaledTouchSlop.toFloat(), it.scaledDoubleTapSlop.toFloat(), ViewConfiguration.getDoubleTapTimeout().toLong())
    }
    private val session = HashMap<String, Any?>()
    private var shownSettings: XrSettings? = null
    private var lastStatus = ""
    private var heardWords = ""
    private var heardPartial = ""
    private var lastV: Float? = null
    private var zoom = 1f
    private var holdAt = 0L
    private var holdX = 0f
    private var holdY = 0f
    private var holding = false
    private var justUnlocked = false
    private var chipTitle = ""
    private var chipDetail = ""
    private var chipAt = 0L
    private var panelPending = false
    private val showPanel = Runnable {
        panelPending = false
        panel?.show(speed, lastV)
    }

    private val controls = FrameLayout(context)

    // The surface shows nothing until its shaders are built and its first frame is
    // drawn, and what's under it is Flutter's own screen, so this stands in until then.
    private var cover: View? = View(context).apply { setBackgroundColor(COVER_COLOR) }
    private val buttons = HashMap<Part, View>()
    private var panel: SpeedPanel? = null
    private var settingsPanel: SettingsPanel? = null
    private var field: TextField? = null

    private val hideChip = Runnable {
        chipTitle = ""
        glass.change { it.hideChip() }
    }
    private val unlock = Runnable {
        justUnlocked = true
        holding = false
        locked = false
        settingsShown = true
        glass.change { it.setHolding(false) }
        host.action("locked", mapOf("value" to false))
        chip("Unlocked")
        relayout()
    }

    init {
        addView(glass, LayoutParams(-1, -1))
        addView(cover, LayoutParams(-1, -1))
        addView(controls, LayoutParams(-1, -1))
    }

    /**
     * With the keyboard up, a touch anywhere but the text field puts it
     * away, and with the settings panel open, a touch anywhere but the panel
     * closes it; the gear closes it itself. On a control it also does what it
     * does; on the pad it does nothing else, rather than clicking.
     */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (settingsShown && dismissSettingsOnDoubleTap(e)) {
            settingsShown = false
            settingsOpen = false
            dismissing = false
            relayout()
            // On the pad, the second tap is half of a remote double-click, so it goes on.
            if (!onAnyControl(e)) return super.dispatchTouchEvent(e)
            // Cancel the child touch so the second tap cannot also click a control.
            val cancel = MotionEvent.obtain(e)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) cancelHold()
        if (e.actionMasked == MotionEvent.ACTION_MOVE && holding && hypot(e.x - holdX, e.y - holdY) > HOLD_SLOP_DP * density) cancelHold()
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            dismissing = false
            val f = field
            val onField = f != null && hits(f, e)
            val onPanel = settingsPanel?.let { hits(it, e) } == true
            val onControl = onAnyControl(e)
            if ((screen?.keyboard ?: 0) > 0 && f != null && !onField) {
                f.hideKeyboard()
                dismissing = !onControl
            }
            if (settingsOpen && !onPanel && buttons[Part.SETTINGS]?.let { hits(it, e) } != true) {
                settingsOpen = false
                relayout()
                dismissing = !onControl && !onField
            }
        }
        if (dismissing) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) dismissing = false
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    private fun onAnyControl(e: MotionEvent) =
        buttons.values.any { hits(it, e) } || panel?.let { hits(it, e) } == true || settingsPanel?.let { hits(it, e) } == true

    private fun dismissSettingsOnDoubleTap(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // Two quick taps on the open panel are for its controls.
            MotionEvent.ACTION_DOWN ->
                if (settingsOpen && settingsPanel?.let { hits(it, e) } == true) doubleTap.cancel() else doubleTap.begin(e.x, e.y, e.eventTime)
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) doubleTap.move(e.getHistoricalX(h), e.getHistoricalY(h))
                doubleTap.move(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> return doubleTap.end(e.x, e.y, e.eventTime)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> doubleTap.cancel()
        }
        return false
    }

    private fun hits(view: View, e: MotionEvent): Boolean {
        if (view.visibility != VISIBLE) return false
        view.getHitRect(hit)
        return hit.contains(e.x.toInt(), e.y.toInt())
    }

    /** The screen's size or insets changed: lay everything out again. */
    fun screenChanged(next: Screen) {
        if (next == screen) return
        val first = screen?.width != next.width
        // The keyboard rising would push the panel off the top of the screen.
        if (next.keyboard > (screen?.keyboard ?: 0)) settingsOpen = false
        screen = next
        unit = next.width / Geometry.ARTBOARD_WIDTH
        if (first) build()
        relayout()
    }

    private fun build() {
        controls.removeAllViews()
        buttons.clear()
        fun button(part: Part, label: String, click: () -> Unit) = View(context).apply {
            contentDescription = label
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
            buttons[part] = this
            controls.addView(this, LayoutParams(0, 0))
        }
        button(Part.BACK, "Close the session") { host.action("back") }
        button(Part.SPEED, "Pointer speed") { toggleSpeed() }
        button(Part.WAKE, "Wake glasses") {
            host.action("wake")
            chip("Waking glasses", "reopening the canvas")
        }
        button(Part.CENTER, "Center pointer") {
            host.action("center")
            chip("Pointer centered", "%d, %d".format((number("width") / 2f).toInt(), (number("height") / 2f).toInt()))
        }
        button(Part.LOCK, "Lock touch surface") { lockClick() }.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> holdStart(e.rawX, e.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> holdEnd()
            }
            false
        }
        button(Part.SETTINGS, "Settings") { toggleSettings() }.visibility = GONE

        val f = TextField(
            context, unit,
            onMic = { host.action("dictate") },
            onClear = ::clearText,
            onKeyboard = { host.action("keyboard") },
            onText = { text ->
                glass.change { it.fieldEmpty = text.isEmpty() }
                host.action("softInput", mapOf("text" to text))
            },
            onKey = { key ->
                host.action("softKey", mapOf("key" to key))
                if (key == TextField.KEY_ENTER) clearField()
            },
        )
        field = f
        controls.addView(f, LayoutParams(0, 0))

        val p = SpeedPanel(context, unit, onSpeed = { speed(it) }, onReset = { speed(3f) }, onDone = { toggleSpeed() })
        p.visibility = GONE
        panel = p
        controls.addView(p, LayoutParams(0, 0))
        p.show(speed, lastV)
        glass.change { it.setSpeed(speedText(), speedOpen) }

        val sp = SettingsPanel(context, unit, this)
        sp.visibility = GONE
        settingsPanel = sp
        controls.addView(sp, LayoutParams(0, 0))
        shownSettings = null
        showSettings()
        applyDictation()
    }

    /** The goals for the current modes, to the glass and the views. */
    private fun relayout() {
        val s = screen ?: return
        val geo = Geometry(s, Modes(locked, speedOpen, dictating, settingsShown, settingsOpen))
        geometry = geo
        glass.change { it.layout(geo) }
        for ((part, view) in buttons) {
            if (part != Part.SETTINGS) place(view, geo.home[part.ordinal])
        }
        if (!settingsShown) buttons[Part.SETTINGS]?.isEnabled = false
        for (part in listOf(Part.BACK, Part.SPEED, Part.WAKE, Part.CENTER)) {
            buttons[part]?.visibility = if (geo.open) VISIBLE else GONE
        }
        buttons[Part.LOCK]?.contentDescription =
            if (locked) "Touch surface locked. Hold for 2 seconds to unlock." else "Lock touch surface"
        field?.let { if (geo.showField) place(it, geo.field) }
        panel?.let { place(it, geo.panelOpen) }
        // Only while open: closing moves its goal as the field goes, and it would jump as it fades.
        settingsPanel?.let { if (geo.settingsOpen) place(it, geo.settingsPanelOpen) }
    }

    private fun place(view: View, box: Box) {
        val lp = view.layoutParams as LayoutParams
        val x = box.x.roundToInt()
        val y = box.y.roundToInt()
        val w = box.w.roundToInt()
        val h = box.h.roundToInt()
        if (lp.leftMargin == x && lp.topMargin == y && lp.width == w && lp.height == h) return
        lp.leftMargin = x
        lp.topMargin = y
        lp.width = w
        lp.height = h
        view.layoutParams = lp
    }

    /** Each frame the glass moved: the text field and the panels follow their glass and fade with it. */
    private fun follow(frame: Scene.Frame) {
        cover?.let {
            removeView(it)
            cover = null
        }
        val geo = geometry ?: return
        field?.let {
            it.alpha = frame.fieldFade
            it.visibility = if (frame.fieldFade > 0.01f) VISIBLE else GONE
            if (geo.showField) {
                // Follow the animated width as the settings droplet splits off;
                // contents keep their height, hanging from the glass's bottom.
                place(it, Box(frame.field.x, frame.field.bottom - geo.field.h, frame.field.w, geo.field.h))
            }
        }
        buttons[Part.SETTINGS]?.let {
            place(it, frame.settings)
            it.visibility = if (settingsShown && frame.settingsFade > 0.9f) VISIBLE else GONE
            it.isEnabled = settingsShown && frame.settingsFade > 0.9f
        }
        panel?.let {
            val open = geo.open && speedOpen
            it.alpha = Scene.ease(0.7f, 1f, frame.panelGrow)
            it.visibility = if (open || frame.panelGrow > 0.05f) VISIBLE else GONE
            it.isEnabled = open
        }
        settingsPanel?.let {
            val open = geo.settingsOpen
            it.alpha = Scene.ease(0.7f, 1f, frame.settingsPanelGrow)
            it.visibility = if (open || frame.settingsPanelGrow > 0.05f) VISIBLE else GONE
            it.isEnabled = open
        }
    }

    // The session.

    private fun number(key: String, fallback: Float = 0f) = (session[key] as? Number)?.toFloat() ?: fallback

    private fun flag(key: String, fallback: Boolean = false) = session[key] as? Boolean ?: fallback

    private fun words(key: String) = session[key] as? String ?: ""

    /** The session's latest state; anything not mentioned stays as it was. */
    fun update(values: Map<String, Any?>) {
        session.putAll(values)
        // Input taken away ends a gesture in progress, and its held button with it.
        if (values["canInput"] == false) glass.cancelGestures()
        applyDictation()
        val status = words("status")
        if (status != lastStatus) {
            lastStatus = status
            if (status.isNotEmpty()) chip(if (status.length > CHIP_MAX_CHARS) status.take(CHIP_MAX_CHARS - 1) + "…" else status)
        }
        showSettings()
    }

    private fun applyDictation() {
        val active = flag("dictationActive")
        val f = field
        if (active != dictating) {
            dictating = active
            if (active) {
                speedOpen = false
                settingsOpen = false
                glass.change { it.setSpeed(speedText(), false) }
            }
            f?.dictating(active)
            chip(if (active) "Dictation on" else "Dictation off")
            relayout()
        }
        if (f == null || !active) return
        f.dictationStopping(flag("dictationStopping"))
        val heard = words("dictationWords")
        val partial = words("dictationPartial")
        if (heard != heardWords || partial != heardPartial || !f.isHearing) {
            heardWords = heard
            heardPartial = partial
            f.heard(heard, partial)
        }
    }

    private fun showSettings() {
        val next = XrSettings(
            glasses = glassesName,
            distance = saved.distance,
            curvature = saved.curvature,
            motionPrediction = saved.motionPrediction,
            speed = speed,
            anchored = flag("anchored", true),
            fit = flag("fit", true),
            fullBuffer = flag("fullSourceBuffer", true),
            panX = number("panX", 0.5f),
            panY = number("panY", 0.5f),
            tracking = words("trackingStatus"),
            status = words("status"),
            info = sessionInfo(),
        )
        if (next == shownSettings) return
        shownSettings = next
        settingsPanel?.show(next)
    }

    private fun sessionInfo(): String {
        val w = number("width").toInt()
        val h = number("height").toInt()
        if (w <= 0 || h <= 0) return ""
        return "$w × $h source\n${number("surfaceWidth").toInt()} × ${number("surfaceHeight").toInt()} buffer → " +
            "${number("outputWidth").toInt()} × ${number("outputHeight").toInt()} output"
    }

    // The settings panel's controls.

    override fun distance(value: Float) {
        saved.distance = value
        screenSettings()
    }

    override fun curvature(value: Float) {
        saved.curvature = value
        screenSettings()
    }

    override fun motionPrediction(on: Boolean) {
        saved.motionPrediction = on
        screenSettings()
    }

    private fun screenSettings() {
        host.screenChanged(saved.distance, saved.curvature, saved.motionPrediction)
        showSettings()
    }

    override fun speed(value: Float) {
        speed = value
        host.action("sensitivity", mapOf("value" to value.toDouble()))
        panel?.show(speed, lastV)
        glass.change { it.setSpeed(speedText(), speedOpen) }
        showSettings()
    }

    override fun anchored(on: Boolean) = host.action("anchored", mapOf("value" to on))

    override fun recenter() {
        host.action("recenter")
        chip("Recentered")
    }

    override fun recheckCalibration() {
        host.action("recheckCalibration")
        chip("Rechecking calibration")
    }

    override fun calibrateStationary() {
        host.action("calibrateStationary")
        chip("Desk calibration", "leave the glasses still")
    }

    override fun fit(on: Boolean) = host.action("fit", mapOf("value" to on))

    override fun fullBuffer(on: Boolean) = host.action("fullBuffer", mapOf("value" to on))

    override fun pan(x: Float, y: Float) = host.action("pan", mapOf("x" to x.toDouble(), "y" to y.toDouble()))

    override fun clearText() {
        clearField()
        host.action("clear")
        chip("Text cleared", "on both screens")
    }

    private fun clearField() {
        field?.clearLocally()
        glass.change { it.fieldEmpty = true }
        host.action("softReset")
    }

    override fun wake() {
        settingsOpen = false
        relayout()
        host.action("wake")
        chip("Waking glasses", "reopening the canvas")
    }

    override fun sessionMenu() {
        settingsOpen = false
        relayout()
        host.action("sessionMenu")
    }

    override fun exitXr() = host.action("exit")

    /** Dart has checked the keyboard is allowed. */
    fun showKeyboard() {
        settingsOpen = false
        relayout()
        field?.showKeyboard()
    }

    /** The back key closes an open panel before anything closes the session. */
    fun back(): Boolean {
        if (!settingsOpen && !speedOpen) return false
        settingsOpen = false
        speedOpen = false
        glass.change { it.setSpeed(speedText(), false) }
        relayout()
        return true
    }

    // The chip.

    private fun chip(title: String, detail: String = "") {
        // A pinch changes its details at every event; 15 a second is plenty to read.
        val now = SystemClock.uptimeMillis()
        if (title == chipTitle && (detail == chipDetail || now - chipAt < CHIP_REFRESH_MS)) {
            rescheduleChip()
            return
        }
        chipTitle = title
        chipDetail = detail
        chipAt = now
        glass.change { it.showChip(title, detail) }
        rescheduleChip()
    }

    private fun rescheduleChip() {
        handler.removeCallbacks(hideChip)
        handler.postDelayed(hideChip, CHIP_MS)
    }

    // Speed.

    private fun speedText() = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

    private fun toggleSpeed() {
        speedOpen = !speedOpen
        if (speedOpen) settingsOpen = false
        panel?.show(speed, lastV)
        glass.change { it.setSpeed(speedText(), speedOpen) }
        relayout()
    }

    // Settings.

    private fun toggleSettings() {
        settingsOpen = !settingsOpen
        if (settingsOpen) {
            field?.hideKeyboard()
            speedOpen = false
            glass.change { it.setSpeed(speedText(), false) }
        }
        relayout()
    }

    // The lock.

    private fun lockClick() {
        if (justUnlocked) {
            justUnlocked = false
            return
        }
        if (locked) return
        speedOpen = false
        settingsOpen = false
        settingsShown = false
        locked = true
        field?.hideKeyboard()
        glass.change { it.setSpeed(speedText(), false) }
        host.action("locked", mapOf("value" to true))
        chip("Locked", "the whole screen is the trackpad")
        relayout()
    }

    private fun holdStart(rawX: Float, rawY: Float) {
        if (!locked) return
        holdAt = SystemClock.uptimeMillis()
        holdX = rawX
        holdY = rawY
        holding = true
        glass.change { it.setHolding(true) }
        host.action("holding", mapOf("value" to true))
        handler.removeCallbacks(unlock)
        handler.postDelayed(unlock, (Scene.HOLD_S * 1000).toLong())
    }

    private fun holdEnd() {
        handler.removeCallbacks(unlock)
        if (!holding) return
        val short = SystemClock.uptimeMillis() - holdAt < 400
        cancelHold()
        if (short) chip("Hold to unlock", "2 seconds")
    }

    /** A second finger, or the finger moving away, leaves the controls locked. */
    private fun cancelHold() {
        handler.removeCallbacks(unlock)
        if (!holding) return
        holding = false
        glass.change { it.setHolding(false) }
        host.action("holding", mapOf("value" to false))
    }

    // The trackpad's gestures. Positions are dp, as GlassView is the whole screen.

    override fun pointer(dx: Float, dy: Float, gain: Float, speed: Float, dragging: Boolean) {
        lastV = speed
        output.move(dx * gain, dy * gain)
        // The open panel shows the last move; 30 times a second is enough,
        // and spares redrawing it for every touch event.
        if (speedOpen && !panelPending) {
            panelPending = true
            handler.postDelayed(showPanel, PANEL_REFRESH_MS)
        }
        // No figures here: words that change with every move would redraw the chip's
        // label, and upload it to the GPU, all the time a finger is moving.
        if (!showingInfinity()) chip(if (dragging) "Dragging" else "Pointer")
    }

    private fun button(secondary: Boolean, down: Boolean) {
        output.flush()
        host.action("button", mapOf("secondary" to secondary, "down" to down))
    }

    override fun click(x: Float, y: Float) {
        button(secondary = false, down = true)
        button(secondary = false, down = false)
        chip("Click")
    }

    override fun dragStart() {
        button(secondary = false, down = true)
        chip("Dragging", "three fingers")
    }

    override fun dragEnd() {
        button(secondary = false, down = false)
        if (!showingInfinity()) chip("Drop", "button released")
    }

    override fun secondaryClick(x: Float, y: Float) {
        button(secondary = true, down = true)
        button(secondary = true, down = false)
        chip("Right-click")
    }

    override fun scroll(dx: Float, dy: Float, momentum: Boolean) {
        output.scroll(dx, dy)
        chip("Scrolling", if (momentum) "momentum" else "natural")
        glass.change { it.legendScroll(dx * density, dy * density) }
    }

    override fun zoom(factor: Float) {
        zoom = (zoom * factor).coerceIn(0.25f, 4f)
        val z = zoom
        chip("Pinch", "zoom %.2f×".format(z))
        glass.change { it.legendZoom(z) }
    }

    override fun dictate() {
        output.flush()
        host.action("dictate")
    }

    override fun isDictating() = dictating

    override fun stopDictation() = host.action("stopDictation")

    override fun finishDictation(how: DictationFinish) {
        host.action("finishDictation", mapOf("how" to how.name))
        chip(
            when (how) {
                DictationFinish.SEND -> "Sent"
                DictationFinish.ENTER -> "Sent with Enter"
                DictationFinish.DISCARD -> "Discarded"
            },
        )
    }

    override fun infinityDouble() {
        chip("Infinity ×2", "within 4 seconds")
        if (settingsShown) return
        settingsShown = true
        doubleTap.cancel()
        relayout()
    }

    private fun showingInfinity() = chipTitle == "Infinity ×2" && SystemClock.uptimeMillis() - chipAt < CHIP_MS

    override fun settle() {
        output.settle()
        zoom = 1f
        glass.change { it.legendRelease() }
    }

    fun onResume() {
        glass.onResume()
        glassesWatcher.start()
    }

    fun onPause() {
        output.flush()
        glassesWatcher.stop()
        doubleTap.cancel()
        handler.removeCallbacks(hideChip)
        handler.removeCallbacks(showPanel)
        panelPending = false
        cancelHold()
        glass.onPause()
    }

    private companion object {
        const val CHIP_MS = 1300L
        const val CHIP_REFRESH_MS = 66L
        const val CHIP_MAX_CHARS = 42
        const val PANEL_REFRESH_MS = 33L
        const val HOLD_SLOP_DP = 12f
        const val COVER_COLOR = 0xFFBDBDC0.toInt()
    }
}
