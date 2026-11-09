package com.carriez.flutter_hbb.xr.glass

import kotlin.math.max
import kotlin.math.min

/** What the layout depends on: the surface's size in px, its density, and the insets that matter, px. */
data class Screen(
    val width: Int,
    val height: Int,
    val density: Float,
    val statusBar: Int,
    val navigationBar: Int,
    val keyboard: Int,
)

/** The trackpad's modes, as far as the layout cares. */
data class Modes(
    val locked: Boolean,
    val speedOpen: Boolean,
    val dictating: Boolean,
    val settingsShown: Boolean = false,
    val settingsOpen: Boolean = false,
)

/**
 * Where every piece of glass belongs, after the Trackpad design's geom():
 * its 412-wide artboard scaled to the screen's width, the top row under the
 * status bar and the text field above the navigation bar or the keyboard.
 *
 * Locked, the pad fills the screen and only the lock remains, in its top
 * right corner. Unlocked, the other buttons have spilled out of the lock into
 * the top row, and the pad makes room for them and the text field. The
 * speed panel pours out of its button and drains back into it, and the
 * settings panel out of the settings droplet.
 */
class Geometry(screen: Screen, modes: Modes) {
    /** Px per dp of the design. */
    val unit = screen.width / ARTBOARD_WIDTH
    val open = !modes.locked
    val showField = open || modes.dictating || modes.settingsShown
    val showSettings = modes.settingsShown
    val settingsOpen = modes.settingsShown && modes.settingsOpen
    val dictating = modes.dictating

    /** The top row's top, px. */
    val row = max(52f * unit, screen.statusBar + 6f * screen.density)

    /** The lowest the text field ever rests: its bottom with no keyboard, px. */
    val fieldFloor = screen.height - max(30f * unit, screen.navigationBar + 8f * screen.density)

    private val fieldBottom = if (screen.keyboard > 0) {
        screen.height - screen.keyboard - 10f * screen.density
    } else {
        fieldFloor
    }

    private fun box(x: Float, y: Float, w: Float, h: Float) = Box(x * unit, y, w * unit, h * unit)

    /** Each button where it sits when unlocked. */
    val home: Array<Box> = arrayOf(
        box(28f, row, 52f, 52f),
        box(121.5f, row, 76f, 52f),
        box(207.5f, row, 52f, 52f),
        box(269.5f, row, 52f, 52f),
        box(331.5f, row, 52f, 52f),
    )

    val lock = home[Part.LOCK.ordinal]

    // As wide as the pad, with its squircle corners (the design's was inset
    // 28 dp, with round ends).
    private val fieldWidth = if (showSettings) 317f else 379f
    val field: Box = when {
        !showField -> Box(186f * unit, fieldBottom + 2f * unit, 40f * unit, 0f)
        modes.dictating -> Box(16.5f * unit, fieldBottom - 144f * unit, fieldWidth * unit, 144f * unit)
        else -> Box(16.5f * unit, fieldBottom - 60f * unit, fieldWidth * unit, 60f * unit)
    }

    /**
     * Shown, a 52 dp round droplet like the top row's, 10 dp right of the
     * text field and centered on its row of controls, 30 dp up from its
     * bottom. Hidden, the same droplet stowed inside the field's right end,
     * so it travels with it.
     */
    val settingsHome = Box(field.right + 10f * unit, field.bottom - 56f * unit, 52f * unit, 52f * unit)
    private val settingsStowed = Box(field.right - 110f * unit, field.bottom - 56f * unit,
        52f * unit, if (showField) 52f * unit else 0f)

    val pad: Box = run {
        val top = if (open) row + 66f * unit else 40f * unit
        val bottom = if (showField) field.y - 14f * unit else screen.height - 16.5f * unit
        Box(16.5f * unit, top, 379f * unit, max(bottom - top, 0f))
    }

    /** The speed panel when it's open: as wide as the pad, with its margins. */
    val panelOpen = box(16.5f, row + 62f * unit, 379f, 380f)

    /**
     * The settings panel when it's open: as wide as the pad, with its margins,
     * and its bottom on the pad's, 14 dp above the text field, however tall
     * that is. Its contents scroll, so it gives up height rather than reach the
     * top row.
     */
    val settingsPanelOpen: Box = run {
        val room = field.y - 14f * unit - (row + 62f * unit)
        val h = max(min(SETTINGS_PANEL_H * unit, room), 0f)
        Box(16.5f * unit, field.y - 14f * unit - h, 379f * unit, h)
    }

    /** The settings panel shut, relative to the settings droplet: its size, or nothing with the field gone too. */
    val settingsPanelStowed = Box(0f, 0f, settingsHome.w, if (showField) settingsHome.h else 0f)

    /** Each part's goal, by [Part]. */
    val goals: Array<Box> = Array(Part.entries.size) { i ->
        when (Part.entries[i]) {
            Part.LOCK -> lock
            Part.PANEL -> if (open && modes.speedOpen) panelOpen else if (open) home[Part.SPEED.ordinal] else lock
            Part.FIELD -> field
            Part.PAD -> pad
            Part.SETTINGS -> if (showSettings) settingsHome else settingsStowed
            // Relative to the droplet, so stowed under it the panel stays put
            // however the text field moves (Scene.shape).
            Part.SETTINGS_PANEL -> if (settingsOpen) {
                Box(settingsPanelOpen.x - settingsHome.x, settingsPanelOpen.y - settingsHome.y, settingsPanelOpen.w, settingsPanelOpen.h)
            } else {
                settingsPanelStowed
            }
            else -> if (open) home[i] else lock
        }
    }

    /**
     * Droplets leave the lock nearest-first when unlocking and come home
     * farthest-first when locking, s.
     */
    val delays = FloatArray(Part.entries.size).also {
        if (open) {
            it[Part.CENTER.ordinal] = 0f
            it[Part.WAKE.ordinal] = 0.045f
            it[Part.SPEED.ordinal] = 0.09f
            it[Part.BACK.ordinal] = 0.135f
            it[Part.FIELD.ordinal] = 0.08f
        } else {
            it[Part.BACK.ordinal] = 0f
            it[Part.SPEED.ordinal] = 0.03f
            it[Part.WAKE.ordinal] = 0.06f
            it[Part.CENTER.ordinal] = 0.09f
        }
        // The settings droplet sets off with the text field, so stowed in it
        // it doesn't poke out ahead as the field moves.
        it[Part.SETTINGS.ordinal] = it[Part.FIELD.ordinal]
    }

    companion object {
        const val ARTBOARD_WIDTH = 412f
        // Its contents scroll inside; this only stops it being needlessly tall.
        const val SETTINGS_PANEL_H = 640f
    }
}
