package com.carriez.flutter_hbb.xr.glass

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Everything on screen that animates, apart from the oil and the marble, on
 * the GL thread: the glass's springs, the chip, the lock's hold ring, the open
 * speed and settings buttons' fills and the legend's scroll and zoom.
 * The UI thread changes it through GLSurfaceView.queueEvent; the renderer
 * advances it once a frame, draws it and hands [Frame] back.
 */
class Scene(private val density: Float, private val reducedMotion: Boolean) {
    var geometry: Geometry? = null
        private set
    private var springs: Springs? = null

    /** The chip: a status line in a droplet of its own at the top of the pad. */
    var chipTitle = ""
        private set
    var chipDetail = ""
        private set
    /** Bumped whenever the chip's words change, so its label is drawn again. */
    var chipVersion = 0
        private set
    private var chipShown = false
    var chipAlpha = 0f
        private set
    /** The chip's width, px, on a spring toward its label's, which the label sets. */
    var chipWidth = 0f
        private set
    var chipLabelWidth = 0f
    private var chipSpeed = 0f

    /** The text field holds no text, so its hint shows (drawn by UiPass). */
    var fieldEmpty = true

    var speedText = "3×"
        private set
    var speedVersion = 0
        private set
    var speedOpen = false
        private set
    /** The open speed button's dark fill, 0 to 1. */
    var fill = 0f
        private set
    /** The settings droplet's dark fill while its panel is open, 0 to 1. */
    var settingsFill = 0f
        private set
    /** The closed lock's icon over the open one, 0 to 1. */
    var lockedLook = 0f
        private set

    private var holding = false
    /** The hold ring's sweep, 0 to 1: filling over 2 s while held, emptying in 0.2 s. */
    var ring = 0f
        private set

    // The legend's scroll, px, and zoom; springing home when let go.
    var legendX = 0f
        private set
    var legendY = 0f
        private set
    var legendZoom = 1f
        private set
    private var legendVx = 0f
    private var legendVy = 0f
    private var legendVz = 0f
    private var legendHome = true

    private var now = 0L

    /** Something is still animating. */
    val busy: Boolean
        get() = springs?.busy == true || chipAlpha != (if (chipShown) 1f else 0f) || abs(chipWidth - chipLabelWidth) > 0.5f ||
            ring != (if (holding) 1f else 0f) || fill != (if (speedOpen) 1f else 0f) ||
            settingsFill != (if (geometry?.settingsOpen == true) 1f else 0f) ||
            lockedLook != (if (geometry?.open == false) 1f else 0f) || !legendResting || fieldGrowth != 0f

    private val legendResting get() = legendHome && legendX == 0f && legendY == 0f && legendZoom == 1f

    // Changes from the UI thread.

    fun layout(next: Geometry) {
        if (next.settingsOpen) settingsPanelDrained = false
        val s = springs ?: Springs(next.unit).also { springs = it }
        s.drive(next.goals, next.delays, next.open, now, reducedMotion || geometry == null)
        geometry = next
    }

    fun showChip(title: String, detail: String) {
        if (title != chipTitle || detail != chipDetail) chipVersion++
        chipTitle = title
        chipDetail = detail
        chipShown = true
    }

    fun hideChip() {
        chipShown = false
    }

    fun setSpeed(text: String, open: Boolean) {
        if (text != speedText) speedVersion++
        speedText = text
        speedOpen = open
    }

    fun setHolding(held: Boolean) {
        holding = held
    }

    /** Two fingers scrolling move the legend with them, a quarter as far and at most 30 dp. */
    fun legendScroll(dx: Float, dy: Float) {
        legendHome = false
        legendVx = 0f
        legendVy = 0f
        val most = LEGEND_REACH_DP * density
        legendX = (legendX + dx * 0.25f).coerceIn(-most, most)
        legendY = (legendY + dy * 0.25f).coerceIn(-most, most)
    }

    fun legendZoom(zoom: Float) {
        legendHome = false
        legendVz = 0f
        legendZoom = zoom.coerceIn(0.85f, 1.25f)
    }

    fun legendRelease() {
        legendHome = true
    }

    // The frame.

    // The text field's height, on a critically damped spring of its own (see shape()).
    private var fieldHeight = Float.NaN
    private var fieldGrowth = 0f

    fun advance(time: Long, dt: Float) {
        now = time
        val s = springs ?: return
        s.step(dt, time)
        val geo = geometry ?: return
        if (!geo.settingsOpen && s.shape(Part.SETTINGS_PANEL).h <= geo.settingsPanelStowed.h) settingsPanelDrained = true
        val heightGoal = if (geo.showField) geo.field.h else 0f
        if (fieldHeight.isNaN() || reducedMotion) {
            fieldHeight = heightGoal
            fieldGrowth = 0f
        } else {
            val w = 2f * PI.toFloat() * FIELD_HZ
            fieldGrowth += (w * w * (heightGoal - fieldHeight) - 2f * w * fieldGrowth) * dt
            fieldHeight += fieldGrowth * dt
            if (abs(heightGoal - fieldHeight) < 0.05f * geo.unit && abs(fieldGrowth) < geo.unit) {
                fieldHeight = heightGoal
                fieldGrowth = 0f
            }
        }

        val chipGoal = if (chipShown) 1f else 0f
        chipAlpha = approach(chipAlpha, chipGoal, dt / if (chipShown) 0.12f else 0.2f)
        if (chipWidth == 0f || reducedMotion) {
            chipWidth = chipLabelWidth
        } else {
            // A droplet's spring, so the chip stretches to new words.
            val w = 2f * PI.toFloat() * 4f
            chipSpeed += (-w * w * (chipWidth - chipLabelWidth) - 2f * 0.55f * w * chipSpeed) * dt
            chipWidth += chipSpeed * dt
            if (abs(chipWidth - chipLabelWidth) < 0.5f && abs(chipSpeed) < 1f) {
                chipWidth = chipLabelWidth
                chipSpeed = 0f
            }
        }

        fill = approach(fill, if (speedOpen) 1f else 0f, dt / 0.15f)
        settingsFill = approach(settingsFill, if (geo.settingsOpen) 1f else 0f, dt / 0.15f)
        lockedLook = approach(lockedLook, if (geo.open) 0f else 1f, dt / 0.18f)
        ring = if (holding) min(1f, ring + dt / HOLD_S) else max(0f, ring - dt / 0.2f)

        if (legendHome) {
            // Critically damped, about the design's 1.2 s ease back.
            val w = 2f * PI.toFloat() * 0.9f
            legendVx += (-w * w * legendX - 2f * w * legendVx) * dt
            legendVy += (-w * w * legendY - 2f * w * legendVy) * dt
            legendVz += (-w * w * (legendZoom - 1f) - 2f * w * legendVz) * dt
            legendX += legendVx * dt
            legendY += legendVy * dt
            legendZoom += legendVz * dt
            val eps = 0.05f * density
            if (abs(legendX) < eps && abs(legendY) < eps && abs(legendVx) < eps && abs(legendVy) < eps &&
                abs(legendZoom - 1f) < 1e-3f && abs(legendVz) < 1e-3f
            ) {
                legendX = 0f
                legendY = 0f
                legendZoom = 1f
            }
        }
    }

    /**
     * Where [part]'s glass is now. The text field, while shown, hangs a fixed
     * gap below the pad's current bottom edge, so it keeps its distance from
     * the pad all through a move rather than lagging and squashing on springs
     * of its own; its height eases on a spring of its own, so it grows
     * smoothly as dictation extends it, and its sides keep their springs, so
     * the settings droplet still splits off. It never passes its floor, its
     * resting bottom with no keyboard: as the pad overshoots on its way down,
     * the field squashes against it instead, and spreads sideways as goo
     * would, up to SQUASH_SPREAD_DP each side when it's flat. The settings
     * droplet rides the field's row, 30 dp up from its bottom, and squashes
     * and spreads with it, so a deep squash closes the 10 dp between them and
     * the goo joins them for a moment. The settings panel's springs are
     * relative to that droplet's resting box, centered where it is now, so the
     * panel rides with the field too, and doesn't move at all while it's
     * stowed under the droplet.
     */
    fun shape(part: Part): Box {
        val s = springs!!
        val raw = s.shape(part)
        val geo = geometry ?: return raw
        if (part == Part.SETTINGS_PANEL) {
            val droplet = shape(Part.SETTINGS)
            val rest = geo.settingsHome
            return Box(droplet.cx - rest.w / 2f + raw.x, droplet.cy - rest.h / 2f + raw.y, raw.w, raw.h)
        }
        if (!geo.showField || (part != Part.FIELD && part != Part.SETTINGS)) return raw
        val gap = geo.field.y - geo.pad.bottom
        val top = s.shape(Part.PAD).bottom + gap
        val eased = if (fieldHeight.isNaN()) geo.field.h else max(fieldHeight, 0f)
        val height = max(min(eased, geo.fieldFloor - top), 0f)
        val squash = if (eased > 0f) height / eased else 1f
        val spread = SQUASH_SPREAD_DP * geo.unit * (1f - squash)
        if (part == Part.FIELD) return Box(raw.x - spread, top, raw.w + 2f * spread, height)
        if (raw.h <= 0.5f) return Box(raw.x, top + height - 30f * geo.unit, raw.w, 0f)
        val row = 52f * geo.unit * squash
        val middle = top + height - 30f * geo.unit * squash
        return Box(raw.x - spread, middle - row / 2f, raw.w + 2f * spread, row)
    }

    val ready get() = springs?.ready == true && geometry != null

    /**
     * What the views over the glass follow: the text field and the speed and
     * settings panels fade with their glass, after the design, and the pad's
     * outline decides where a touch starts a gesture.
     */
    class Frame(
        val pad: Box,
        val field: Box,
        val fieldFade: Float,
        val panelGrow: Float,
        val settings: Box,
        val settingsFade: Float,
        val settingsPanelGrow: Float,
    )

    fun frame(): Frame {
        val geo = geometry!!
        val panel = shape(Part.PANEL)
        val closed = geo.home[Part.SPEED.ordinal].h
        val grow = ((panel.h - closed) / (geo.panelOpen.h - closed)).coerceIn(0f, 1f)
        val stowed = geo.settingsHome.h
        val settingsGrow = ((springs!!.shape(Part.SETTINGS_PANEL).h - stowed) / max(geo.settingsPanelOpen.h - stowed, 1f)).coerceIn(0f, 1f)
        return Frame(shape(Part.PAD), shape(Part.FIELD), fieldFade(), grow, shape(Part.SETTINGS), settingsFade(), settingsGrow)
    }

    /**
     * The settings panel has come out of its droplet. Until then it's left
     * out of the goo, where it would only cost time; it starts and ends the
     * size of the droplet, so nothing shows when it joins or leaves. Once it
     * has drained it stays out of the goo until it next opens, or its spring's
     * rebound would blink where the droplet was when locking took that too.
     */
    val settingsPanelOut get() = !settingsPanelDrained && springs!!.shape(Part.SETTINGS_PANEL).h > geometry!!.settingsPanelStowed.h + 0.5f
    private var settingsPanelDrained = true

    /**
     * The gear appears only as its droplet emerges from the input's right
     * edge. Not with the field's own fade, which dips as its glass squashes
     * and blinked the gear out; UiPass crops it to its glass instead.
     */
    fun settingsFade(): Float {
        val geo = geometry!!
        if (!geo.showField) return 0f
        val button = shape(Part.SETTINGS)
        val field = shape(Part.FIELD)
        val emerged = (button.right - field.right) / (62f * geo.unit)
        return ease(0.55f, 0.95f, emerged)
    }

    /** How far the text field's contents show: they fade in as its glass grows to its size. */
    fun fieldFade(): Float {
        val geo = geometry!!
        if (!geo.showField) return 0f
        val field = shape(Part.FIELD)
        return ease(0.5f, 0.95f, min(field.h / (60f * geo.unit), field.w / (317f * geo.unit)))
    }

    private fun approach(value: Float, goal: Float, step: Float): Float {
        if (reducedMotion) return goal
        return if (value < goal) min(goal, value + step) else max(goal, value - step)
    }

    companion object {
        // The text field's height spring, Hz: as quick as the sheets' leading edges.
        const val FIELD_HZ = 2.5f
        // How far the text field and the settings droplet spread each side
        // when squashed flat against the floor, dp of the design.
        const val SQUASH_SPREAD_DP = 8f
        const val HOLD_S = 2f
        const val LEGEND_REACH_DP = 30f

        fun ease(a: Float, b: Float, x: Float): Float {
            val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return u * u * (3f - 2f * u)
        }
    }
}
