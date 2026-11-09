package com.carriez.flutter_hbb.xr.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.opengl.GLES30.*
import android.opengl.GLUtils
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the trackpad's controls (ui.frag) over the oil's composite, in the
 * same framebuffer: the goo of droplets, their icons, the chip, the hold ring
 * Only the boxes around them are drawn, scissored.
 *
 * The icons and the labels, the speed button's and the chip's, which
 * change, and the text field's hint, live in one atlas texture; a label is
 * drawn again, into its slot, only when its words change.
 *
 * The text field's controls at rest, its keyboard icon, hint and mic, are
 * drawn here with its glass rather than by its views, so they move and fade
 * with it as the keyboard comes and goes. While dictating, its views draw
 * them.
 */
class UiPass(
    build: (name: String, chunks: List<String>) -> Program,
    private val density: Float,
    private val squircle: SquircleCorner,
) {
    private val program = build("ui", listOf("ui.frag"))

    private class Slot(val x: Int, val y: Int, val w: Int, val h: Int) {
        /** Where its label is drawn before it's uploaded, kept for the next words. */
        val bitmap: Bitmap by lazy { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        val canvas by lazy { Canvas(bitmap) }
    }

    private val icons = listOf(
        Icon.BACK to 22f, Icon.WAKE to 21f, Icon.CENTER to 21f, Icon.LOCK to 20f, Icon.UNLOCK to 20f,
        Icon.KEYBOARD to FIELD_KEYBOARD_DP, Icon.MIC to FIELD_MIC_DP,
        Icon.SETTINGS to 22f,
    )
    private val iconSlots = HashMap<Icon, Slot>()
    private lateinit var speedDotsSlot: Slot
    private lateinit var speedSlot: Slot
    private lateinit var chipSlot: Slot
    private lateinit var hintSlot: Slot
    private var atlasW = 0
    private var atlasH = 0
    private var atlas = 0

    private var speedVersion = -1
    private var speedLabelW = 0f
    private var chipVersion = -1
    private var chipLabelW = 0f

    private val drops = FloatArray(MAX_DROPS * 4)
    private val radii = FloatArray(MAX_DROPS)
    private val powers = FloatArray(MAX_DROPS)
    private val erode = FloatArray(MAX_DROPS)
    private val goo = FloatArray(MAX_DROPS)
    private val iconRects = FloatArray(MAX_ICONS * 4)
    private val iconUvs = FloatArray(MAX_ICONS * 4)
    private val iconColors = FloatArray(MAX_ICONS * 4)
    private val regions = ArrayList<FloatArray>()

    private var width = 0
    private var height = 0

    /** Builds the atlas and sets the uniforms that only depend on the screen. Needs a current GL context. */
    fun resize(w: Int, h: Int) {
        width = w
        height = h
        if (atlas != 0) glDeleteTextures(1, intArrayOf(atlas), 0)
        val pad = 2
        fun px(dp: Float) = ceil(dp * density).toInt()
        var x = pad
        for ((icon, size) in icons) {
            iconSlots[icon] = Slot(x, pad, px(size), px(size * icon.viewH / icon.viewW))
            x += px(size) + pad
        }
        speedDotsSlot = Slot(x, pad, px(22f), px(14f))
        x += px(22f) + pad
        speedSlot = Slot(x, pad, px(SPEED_LABEL_W_DP), px(LABEL_H_DP))
        x += speedSlot.w + pad
        val rowH = px(LABEL_H_DP) + 2 * pad
        chipSlot = Slot(pad, rowH + pad, px(CHIP_LABEL_W_DP), px(LABEL_H_DP))
        hintSlot = Slot(pad, chipSlot.y + chipSlot.h + pad, px(HINT_W_DP), px(LABEL_H_DP))
        atlasW = max(x, chipSlot.w + 2 * pad)
        atlasH = hintSlot.y + hintSlot.h + pad

        val bitmap = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for ((icon, _) in icons) {
            val s = iconSlots.getValue(icon)
            icon.draw(canvas, s.x.toFloat(), s.y.toFloat(), s.w.toFloat(), s.h.toFloat(), Color.WHITE)
        }
        Icon.SPEED.draw(canvas, speedDotsSlot.x.toFloat(), speedDotsSlot.y.toFloat(), speedDotsSlot.w.toFloat(), speedDotsSlot.h.toFloat(), Color.WHITE)
        // The hint, in white to be tinted, as the field's EditText would set it.
        val hint = Type.paint(android.graphics.Typeface.DEFAULT, 17f, density, Color.WHITE)
        val hintBaseline = hintSlot.y + hintSlot.h / 2f - (hint.fontMetrics.ascent + hint.fontMetrics.descent) / 2f
        canvas.drawText(HINT, hintSlot.x.toFloat(), hintBaseline, hint)
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        atlas = ids[0]
        glBindTexture(GL_TEXTURE_2D, atlas)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        speedVersion = -1
        chipVersion = -1

        program.use()
        program.set("uRes", w.toFloat(), h.toFloat())
        program.set("uDp", density)
        program.setInt("uBgFrost", 0)
        program.setInt("uAtlas", 1)
    }

    private val titlePaint = Type.paint(Type.semibold, 15f, density, Type.INK)
    private val detailPaint = Type.paint(Type.medium, 15f, density, Type.SECONDARY)
    private val speedPaint = Type.paint(Type.semibold, 15f, density, Color.WHITE)

    /** The chip's width for [scene]'s words, px; drawn into its slot when they change. */
    private fun chipLabel(scene: Scene) {
        if (scene.chipVersion == chipVersion) return
        chipVersion = scene.chipVersion
        val title = titlePaint
        val detail = detailPaint
        val dot = 7f * density
        val gap = 8f * density
        val titleW = title.measureText(scene.chipTitle)
        val detailW = if (scene.chipDetail.isEmpty()) 0f else gap + detail.measureText(scene.chipDetail)
        chipLabelW = min(dot + gap + titleW + detailW, chipSlot.w.toFloat())
        drawLabel(chipSlot) { canvas, baseline ->
            canvas.drawCircle(dot / 2f, chipSlot.h / 2f, dot / 2f, title)
            canvas.drawText(scene.chipTitle, dot + gap, baseline, title)
            if (scene.chipDetail.isNotEmpty()) canvas.drawText(scene.chipDetail, dot + gap + titleW + gap, baseline, detail)
        }
        scene.chipLabelWidth = (CHIP_PAD_LEFT_DP + CHIP_PAD_RIGHT_DP) * density + chipLabelW
    }

    private fun speedLabel(scene: Scene) {
        if (scene.speedVersion == speedVersion) return
        speedVersion = scene.speedVersion
        speedLabelW = min(speedPaint.measureText(scene.speedText), speedSlot.w.toFloat())
        drawLabel(speedSlot) { canvas, baseline -> canvas.drawText(scene.speedText, 0f, baseline, speedPaint) }
    }

    private fun drawLabel(slot: Slot, draw: (Canvas, Float) -> Unit) {
        android.os.Trace.beginSection("label")
        slot.canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        draw(slot.canvas, slot.h / 2f - (titlePaint.fontMetrics.ascent + titlePaint.fontMetrics.descent) / 2f)
        glBindTexture(GL_TEXTURE_2D, atlas)
        GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, slot.x, slot.y, slot.bitmap)
        android.os.Trace.endSection()
    }

    /** Draws [scene]'s controls over the screen, which must be bound; [frost] is the frosted background. */
    fun draw(scene: Scene, frost: Int, lightX: Float, lightY: Float) {
        val geo = scene.geometry ?: return
        chipLabel(scene)
        speedLabel(scene)
        val unit = geo.unit
        val lock = scene.shape(Part.LOCK)
        val speed = scene.shape(Part.SPEED)
        val settings = scene.shape(Part.SETTINGS)

        // The goo: each button reaches full goo only once it's out of the
        // lock, and each panel once it's out of its button, since a smooth
        // minimum of shapes lying on top of each other swells them. The lock
        // goes first, so nothing is merged into it while they're stowed.
        var count = 0
        val fieldNow = scene.shape(Part.FIELD)
        for (part in DROPS) {
            val b = scene.shape(part)
            if (b.w < 0.5f || b.h < 0.5f) continue
            // Hidden settings stays stowed in the field; drawn only while it
            // merges back, so it can't poke out of the field as it moves.
            if (part == Part.SETTINGS && !geo.showSettings && b.right <= fieldNow.right) continue
            if (part == Part.SETTINGS_PANEL && !scene.settingsPanelOut) continue
            val anchor = when (part) {
                Part.PANEL -> speed
                Part.SETTINGS_PANEL -> settings
                Part.FIELD -> null
                Part.LOCK -> null
                else -> lock
            }
            // The text field's and the panels' corners are the pad's
            // squircle; the others, the settings droplet's too, are the
            // design's circular arcs. A panel
            // keeps round ends while it's small enough to merge with its
            // button, and turns squircle as it opens.
            val corner = when (part) {
                Part.PANEL, Part.SETTINGS_PANEL, Part.FIELD -> squircle.extentDp * density
                else -> Float.MAX_VALUE
            }
            powers[count] = when (part) {
                Part.FIELD -> squircle.exponent
                Part.PANEL, Part.SETTINGS_PANEL -> mix(2f, squircle.exponent, Scene.ease(1f, 2f, min(b.w, b.h) / (2f * corner)))
                else -> 2f
            }
            drops[count * 4] = b.cx
            drops[count * 4 + 1] = b.cy
            drops[count * 4 + 2] = b.w / 2f
            drops[count * 4 + 3] = b.h / 2f
            radii[count] = min(corner, min(b.w, b.h) / 2f)
            erode[count] = max(0f, ERODE_DP * density - min(b.w, b.h) / 2f)
            val apart = when {
                part == Part.SETTINGS -> max(0f, b.right - scene.shape(Part.FIELD).right)
                anchor == null -> Float.MAX_VALUE
                else -> hypot(b.cx - anchor.cx, b.cy - anchor.cy) + abs(b.h - anchor.h)
            }
            goo[count] = GOO * unit * Scene.ease(0f, 8f * unit, apart)
            count++
        }

        var icons = 0
        fun icon(slot: Slot, x: Float, y: Float, color: Int, white: Float, opacity: Float) {
            if (opacity <= 0.002f || icons >= MAX_ICONS) return
            val i = icons++
            iconRects[i * 4] = floor(x + 0.5f)
            iconRects[i * 4 + 1] = floor(y + 0.5f)
            iconRects[i * 4 + 2] = slot.w.toFloat()
            iconRects[i * 4 + 3] = slot.h.toFloat()
            iconUvs[i * 4] = slot.x.toFloat() / atlasW
            iconUvs[i * 4 + 1] = slot.y.toFloat() / atlasH
            iconUvs[i * 4 + 2] = (slot.x + slot.w).toFloat() / atlasW
            iconUvs[i * 4 + 3] = (slot.y + slot.h).toFloat() / atlasH
            iconColors[i * 4] = mix(Color.red(color) / 255f, 1f, white)
            iconColors[i * 4 + 1] = mix(Color.green(color) / 255f, 1f, white)
            iconColors[i * 4 + 2] = mix(Color.blue(color) / 255f, 1f, white)
            iconColors[i * 4 + 3] = opacity
        }
        // Each icon rides its droplet, and only shows once the droplet has
        // mostly left the lock.
        val lockX = geo.lock.cx
        fun ride(part: Part): Pair<Float, Float> {
            val b = scene.shape(part)
            val home = geo.home[part.ordinal]
            val span = abs(home.cx - lockX).takeIf { it > 0f } ?: 1f
            return Pair(b.cx, Scene.ease(0.55f, 0.95f, abs(b.cx - lockX) / span))
        }
        fun centered(part: Part, slot: Slot, color: Int = Type.INK) {
            val (cx, opacity) = ride(part)
            val home = geo.home[part.ordinal]
            icon(slot, cx - slot.w / 2f, home.cy - slot.h / 2f, color, 0f, opacity)
        }
        centered(Part.BACK, iconSlots.getValue(Icon.BACK))
        centered(Part.WAKE, iconSlots.getValue(Icon.WAKE))
        centered(Part.CENTER, iconSlots.getValue(Icon.CENTER))
        val (speedX, speedOpacity) = ride(Part.SPEED)
        val speedHome = geo.home[Part.SPEED.ordinal]
        val contentW = speedDotsSlot.w + SPEED_GAP_DP * density + speedLabelW
        val speedLeft = speedX - contentW / 2f
        icon(speedDotsSlot, speedLeft, speedHome.cy - speedDotsSlot.h / 2f, Type.INK, scene.fill, speedOpacity)
        icon(speedSlot, speedLeft + speedDotsSlot.w + SPEED_GAP_DP * density, speedHome.cy - speedSlot.h / 2f, Type.INK, scene.fill, speedOpacity)
        val closed = iconSlots.getValue(Icon.LOCK)
        icon(closed, geo.lock.cx - closed.w / 2f, geo.lock.cy - closed.h / 2f, Type.INK, 0f, scene.lockedLook)
        icon(iconSlots.getValue(Icon.UNLOCK), geo.lock.cx - closed.w / 2f, geo.lock.cy - closed.h / 2f, Type.INK, 0f, 1f - scene.lockedLook)

        // The settings gear, stretched with its droplet as the field's
        // controls are with theirs (uGear, uGearMap). White on its dark fill
        // while its panel is open.
        val settingsRest = geo.goals[Part.SETTINGS.ordinal]
        val gear = iconSlots.getValue(Icon.SETTINGS)
        val gearIndex = icons
        icon(gear, settings.cx - gear.w / 2f, settings.cy - gear.h / 2f, Type.INK, scene.settingsFill, scene.settingsFade())
        val gearShown = icons > gearIndex

        // The text field's controls, laid out as its views are (TextField):
        // a 48 dp row, the keyboard icon and the mic in 48 dp squares 6 dp
        // inside either end, the hint after the keyboard's square and the
        // EditText's 4 dp padding. They're laid out in the field's glass as it
        // rests, centered where it is now, and ui.frag stretches them as the
        // glass is stretched (uFieldMap), so they squash and stretch with its
        // jelly spring, the mic's disc and all. At rest the row's middle is
        // where the views have it, 6 dp up from the glass's bottom. They
        // don't fade as the glass bounces: ui.frag crops them to it, softly
        // (uClipFrom).
        val field = scene.shape(Part.FIELD)
        val rest = geo.field
        val fieldOpacity = if (geo.showField && !geo.dictating && rest.h > 0.5f) 1f else 0f
        val clipFrom = icons
        val rowY = field.cy
        val restLeft = field.cx - rest.w / 2f
        val keyboardX = restLeft + (FIELD_INSET_DP + FIELD_ROW_DP / 2f) * unit
        val micX = field.cx + rest.w / 2f - (FIELD_INSET_DP + FIELD_ROW_DP / 2f) * unit
        val keyboardSlot = iconSlots.getValue(Icon.KEYBOARD)
        icon(keyboardSlot, keyboardX - keyboardSlot.w / 2f, rowY - keyboardSlot.h / 2f, Type.INK, 0f, fieldOpacity)
        icon(
            hintSlot, restLeft + (FIELD_INSET_DP + FIELD_ROW_DP + 4f) * unit, rowY - hintSlot.h / 2f,
            Type.SECONDARY, 0f, if (scene.fieldEmpty) fieldOpacity else 0f,
        )
        val micSlot = iconSlots.getValue(Icon.MIC)
        icon(micSlot, micX - micSlot.w / 2f, rowY - micSlot.h / 2f, Color.WHITE, 1f, fieldOpacity)

        program.use()
        program.set("uLightDir", lightX, lightY)
        program.setInt("uDropCount", count)
        program.setVec4s("uDrops", drops)
        program.setFloats("uDropRadii", radii)
        program.setFloats("uDropPowers", powers)
        program.setFloats("uDropErode", erode)
        program.setFloats("uDropGoo", goo)
        program.setInt("uIconCount", icons)
        program.setInt("uClipFrom", clipFrom)
        program.setInt("uGear", if (gearShown) gearIndex else -1)
        program.set("uGearMap", settings.cx, settings.cy, settings.w / max(settingsRest.w, 1f), settings.h / max(settingsRest.h, 1f))
        program.setVec4s("uIcons", iconRects)
        program.setVec4s("uIconUvs", iconUvs)
        program.setVec4s("uIconColors", iconColors)
        program.set("uFill", speed.cx, speed.cy, speed.w / 2f, speed.h / 2f)
        program.set("uFillAlpha", scene.fill * speedOpacity)
        program.set("uGearFill", settings.cx, settings.cy, settings.w / 2f, settings.h / 2f)
        program.set("uGearFillAlpha", scene.settingsFill * scene.settingsFade())
        program.set("uDisc", micX, rowY, FIELD_ROW_DP / 2f * unit, fieldOpacity)
        program.set("uFieldMap", field.cx, field.cy, field.w / max(rest.w, 1f), field.h / max(rest.h, 1f))
        program.set("uRing", geo.lock.cx, geo.lock.cy, 22f * unit, scene.ring)
        program.set("uRingAlpha", if (scene.ring > 0f) 1f else 0f)

        // The chip sits at the pad's top, and makes way for the speed panel.
        // Locked, the pad reaches up past the lock, so the chip stays below
        // the lock's row rather than covering it.
        val pad = scene.shape(Part.PAD)
        val chipAlpha = scene.chipAlpha * (1f - scene.fill)
        val chipH = CHIP_H_DP * density
        val chipW = scene.chipWidth
        val chipX = width / 2f
        val chipY = max(pad.y + 14f * unit, geo.lock.bottom + 8f * unit) + chipH / 2f
        program.set("uChip", chipX, chipY, chipW / 2f, chipH / 2f)
        program.set("uChipAlpha", chipAlpha)
        program.set(
            "uChipLabel",
            floor(chipX - chipW / 2f + CHIP_PAD_LEFT_DP * density + 0.5f),
            floor(chipY - chipSlot.h / 2f + 0.5f),
            chipSlot.w.toFloat(),
            chipSlot.h.toFloat(),
        )
        program.set(
            "uChipLabelUv",
            chipSlot.x.toFloat() / atlasW,
            chipSlot.y.toFloat() / atlasH,
            (chipSlot.x + chipSlot.w).toFloat() / atlasW,
            (chipSlot.y + chipSlot.h).toFloat() / atlasH,
        )

        bindTexture(0, frost)
        bindTexture(1, atlas)

        // Where to draw: the top row with the panel, the ring and the chip;
        // the text field. Margins hold the goo's bulges and the
        // shadow, which is offset 9 dp down and blurred about 21 dp, and the
        // chip's, 10 dp and about 27 dp.
        regions.clear()
        val above = ABOVE_DP * density
        val margin = SIDE_DP * density
        val below = BELOW_DP * density
        fun add(l: Float, t: Float, r: Float, b: Float) {
            regions += floatArrayOf(max(0f, l), max(0f, t), min(width.toFloat(), r), min(height.toFloat(), b))
        }
        val panel = scene.shape(Part.PANEL)
        var l = lock.x
        var t = lock.y
        var r = lock.right
        var b = lock.bottom
        for (part in TOP) {
            val s = scene.shape(part)
            l = min(l, s.x); t = min(t, s.y); r = max(r, s.right); b = max(b, s.bottom)
        }
        if (panel.h > speed.h + 0.5f) {
            l = min(l, panel.x); t = min(t, panel.y); r = max(r, panel.right); b = max(b, panel.bottom)
        }
        if (chipAlpha > 0f) {
            val shade = CHIP_SHADOW_DP * density
            l = min(l, chipX - chipW / 2f - shade); r = max(r, chipX + chipW / 2f + shade); b = max(b, chipY + chipH / 2f + shade)
        }
        add(l - margin, t - above, r + margin, b + below)
        if (field.h > 0.5f) add(field.x - margin, field.y - above, field.right + margin, field.bottom + below)
        if (settings.h > 0.5f) add(settings.x - margin, settings.y - above, settings.right + margin, settings.bottom + below)
        if (scene.settingsPanelOut) {
            val p = scene.shape(Part.SETTINGS_PANEL)
            add(p.x - margin, p.y - above, p.right + margin, p.bottom + below)
        }
        mergeRegions()

        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_SCISSOR_TEST)
        for (region in regions) {
            val x0 = floor(region[0]).toInt()
            val y0 = floor(region[1]).toInt()
            val x1 = ceil(region[2]).toInt()
            val y1 = ceil(region[3]).toInt()
            if (x1 <= x0 || y1 <= y0) continue
            // GL's y runs up from the bottom.
            glScissor(x0, height - y1, x1 - x0, y1 - y0)
            drawFullscreen()
        }
        glDisable(GL_SCISSOR_TEST)
        glDisable(GL_BLEND)
    }

    /** Overlapping boxes would blend twice, so merge them into their bounds. */
    private fun mergeRegions() {
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in regions.indices) {
                for (j in i + 1 until regions.size) {
                    val a = regions[i]
                    val c = regions[j]
                    if (a[0] < c[2] && c[0] < a[2] && a[1] < c[3] && c[1] < a[3]) {
                        a[0] = min(a[0], c[0]); a[1] = min(a[1], c[1]); a[2] = max(a[2], c[2]); a[3] = max(a[3], c[3])
                        regions.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
        }
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val MAX_DROPS = 9 // ui.frag's
        const val MAX_ICONS = 11
        val DROPS = listOf(Part.LOCK, Part.BACK, Part.SPEED, Part.WAKE, Part.CENTER, Part.PANEL, Part.FIELD, Part.SETTINGS, Part.SETTINGS_PANEL)
        val TOP = listOf(Part.BACK, Part.SPEED, Part.WAKE, Part.CENTER)
        // How near droplets come before they flow together, dp of the design.
        const val GOO = 14f
        // Droplets thinner than twice this shrink away, as the design's
        // blurred, thresholded goo loses them.
        const val ERODE_DP = 6f
        // The text field's row, as TextField lays it out, dp of the design.
        const val FIELD_INSET_DP = 6f
        const val FIELD_ROW_DP = 48f
        const val FIELD_KEYBOARD_DP = 24f
        const val FIELD_MIC_DP = 20f
        const val HINT = "Type or dictate"
        const val HINT_W_DP = 160f
        const val SPEED_GAP_DP = 7f
        const val SPEED_LABEL_W_DP = 56f
        const val CHIP_LABEL_W_DP = 380f
        const val LABEL_H_DP = 24f
        const val CHIP_H_DP = 38f
        const val CHIP_PAD_LEFT_DP = 13f
        const val CHIP_PAD_RIGHT_DP = 16f
        const val ABOVE_DP = 14f
        const val SIDE_DP = 22f
        const val BELOW_DP = 31f
        const val CHIP_SHADOW_DP = 28f
    }
}
