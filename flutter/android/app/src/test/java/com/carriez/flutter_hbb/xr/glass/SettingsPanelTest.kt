package com.carriez.flutter_hbb.xr.glass

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

class SettingsPanelTest {
    private val screen = Screen(1080, 2424, 2.625f, 84, 72, 0)
    private fun geometry(open: Boolean, shown: Boolean = true, locked: Boolean = false, keyboard: Int = 0, dictating: Boolean = false, height: Int = screen.height) =
        Geometry(screen.copy(keyboard = keyboard, height = height), Modes(locked, false, dictating, shown, open))

    // The screen's settings, as Mac XR's panel shows them.

    @Test fun distanceSnapsToOneToOne() {
        assertEquals(1f, ScreenSettings.snapDistance(1.03f), 0f)
        assertEquals(1f, ScreenSettings.snapDistance(0.97f), 0f)
        assertEquals(1.05f, ScreenSettings.snapDistance(1.05f), 0f)
        assertEquals(0.95f, ScreenSettings.snapDistance(0.95f), 0f)
    }

    @Test fun distanceAndCurvatureRead() {
        assertEquals("1.00× · 1:1 pixels", ScreenSettings.distanceText(1f))
        assertEquals("1.25×", ScreenSettings.distanceText(1.25f))
        assertEquals("Flat", ScreenSettings.curvatureText(0f))
        assertEquals("37%", ScreenSettings.curvatureText(0.37f))
        assertEquals("100% · around you", ScreenSettings.curvatureText(1f))
        assertEquals("100% · around you", ScreenSettings.curvatureText(0.99999994f))
    }

    @Test fun flatScreenIsAStraightPlaneAtItsDistance() {
        val arc = ScreenSettings.arc(2f, 0f)
        assertArrayEquals(floatArrayOf(2f, -ScreenSettings.WIDTH / 2f, 2f, ScreenSettings.WIDTH / 2f), arc, 1e-5f)
    }

    @Test fun curvedScreenKeepsItsWidthAndCenter() {
        val arc = ScreenSettings.arc(1.5f, 0.6f)
        var along = 0f
        for (i in 2 until arc.size step 2) along += hypot(arc[i] - arc[i - 2], arc[i + 1] - arc[i - 1])
        assertEquals(ScreenSettings.WIDTH, along, 1e-3f)
        // Its middle stays straight ahead, at its distance.
        val middle = arc.size / 2 - 1
        assertEquals(1.5f, arc[middle], 1e-4f)
        assertEquals(0f, arc[middle + 1], 1e-4f)
    }

    @Test fun fullCurvatureWrapsAroundTheWearer() {
        val arc = ScreenSettings.arc(1.25f, 1f)
        for (i in arc.indices step 2) assertEquals(1.25f, hypot(arc[i], arc[i + 1]), 1e-4f)
        val spread = atan2(arc[arc.size - 1], arc[arc.size - 2]) - atan2(arc[1], arc[0])
        assertEquals(ScreenSettings.WIDTH / 1.25f, spread, 1e-4f)
    }

    @Test fun steppedLandsOnItsEndsExactlyAndKeepsTheSpeedGrid() {
        assertEquals(1f, stepped(1f, 0f, 1f, 0.01f), 0f)
        assertEquals(0f, stepped(0f, 0f, 1f, 0.01f), 0f)
        assertEquals(3f, stepped(3f, 0.5f, 3f, 0.01f), 1e-5f)
        assertEquals(3.25f, stepped(3.3f, 0.5f, 8f, 0.25f), 0f)
        assertEquals(0.5f, stepped(0.1f, 0.5f, 8f, 0.25f), 0f)
        assertEquals(8f, stepped(9f, 0.5f, 8f, 0.25f), 0f)
    }

    // Whether the glasses are connected.

    private val builtIn = listOf("Built-in Screen")

    @Test fun noGlassesWithNothingPluggedIn() {
        assertNull(glassesName(builtIn, emptyList()))
    }

    @Test fun otherExternalDisplaysAndUsbDevicesAreNotGlasses() {
        assertNull(glassesName(builtIn + "HDMI Screen", listOf(UsbInfo(0x1234, "Keyboard"))))
    }

    @Test fun glassesAreKnownByDisplayName() {
        assertEquals("XREAL One", glassesName(builtIn + " XREAL One ", emptyList()))
        assertEquals("nreal Air", glassesName(builtIn + "nreal Air", emptyList()))
    }

    @Test fun glassesAreKnownByUsbVendor() {
        assertEquals("XREAL 1S", glassesName(builtIn, listOf(UsbInfo(0x3318, "XREAL 1S"))))
        assertEquals("XREAL glasses", glassesName(builtIn, listOf(UsbInfo(0x3318, null))))
        assertEquals("XREAL glasses", glassesName(builtIn, listOf(UsbInfo(0x3318, "  "))))
    }

    @Test fun displayNameIsPreferred() {
        assertEquals("XREAL One", glassesName(builtIn + "XREAL One", listOf(UsbInfo(0x3318, "Other"))))
    }

    // Where the panel goes.

    @Test fun openPanelMatchesThePadAndClearsTheField() {
        for (locked in listOf(false, true)) for (dictating in listOf(false, true)) {
            val geo = geometry(true, locked = locked, dictating = dictating)
            val panel = geo.settingsPanelOpen
            assertEquals(geo.pad.x, panel.x, 0.01f)
            assertEquals(geo.pad.w, panel.w, 0.01f)
            assertEquals(geo.field.y - 14f * geo.unit, panel.bottom, 0.01f)
            assertTrue(panel.y >= geo.lock.bottom)
            assertTrue(panel.right <= screen.width)
        }
    }

    @Test fun panelGivesUpHeightOnAShortScreen() {
        val tall = geometry(true)
        assertEquals(Geometry.SETTINGS_PANEL_H * tall.unit, tall.settingsPanelOpen.h, 0.01f)
        val short = geometry(true, height = 1500)
        assertTrue(short.settingsPanelOpen.h < tall.settingsPanelOpen.h)
        assertTrue(short.settingsPanelOpen.y >= short.lock.bottom)
    }

    @Test fun panelOnlyOpensWithItsDroplet() {
        assertFalse(geometry(true, shown = false).settingsOpen)
        assertTrue(geometry(true).settingsOpen)
        assertFalse(geometry(false).settingsOpen)
    }

    // How it moves.

    private class Run(val scene: Scene) {
        private var time = 0L
        fun step(frames: Int) = repeat(frames) {
            time += 8_333_333L
            scene.advance(time, 1f / 120)
        }
    }

    private fun running(vararg start: Geometry, reduced: Boolean = false): Run {
        val run = Run(Scene(screen.density, reduced))
        start.forEach(run.scene::layout)
        run.step(600)
        return run
    }

    @Test fun opensToItsPlaceAndDrainsBack() {
        val run = running(geometry(false))
        val scene = run.scene
        assertFalse(scene.settingsPanelOut)
        assertEquals(0f, scene.frame().settingsPanelGrow, 0.001f)
        assertEquals(0f, scene.settingsFill, 0.001f)

        val open = geometry(true)
        scene.layout(open)
        run.step(600)
        assertFalse(scene.busy)
        assertTrue(scene.settingsPanelOut)
        assertTrue(scene.shape(Part.SETTINGS_PANEL).same(open.settingsPanelOpen, 0.25f))
        assertEquals(1f, scene.frame().settingsPanelGrow, 0.001f)
        assertEquals(1f, scene.settingsFill, 0.001f)

        scene.layout(geometry(false))
        run.step(600)
        assertFalse(scene.busy)
        assertFalse(scene.settingsPanelOut)
        assertEquals(0f, scene.frame().settingsPanelGrow, 0.001f)
        assertEquals(0f, scene.settingsFill, 0.001f)
    }

    @Test fun canReverseMidway() {
        val run = running(geometry(false))
        val scene = run.scene
        scene.layout(geometry(true))
        run.step(12)
        assertTrue(scene.busy)
        scene.layout(geometry(false))
        run.step(600)
        assertFalse(scene.settingsPanelOut)
        scene.layout(geometry(true))
        run.step(600)
        assertTrue(scene.shape(Part.SETTINGS_PANEL).same(geometry(true).settingsPanelOpen, 0.25f))
    }

    @Test fun stowedPanelStaysHiddenWhileTheFieldMoves() {
        val run = running(geometry(false))
        val scene = run.scene
        scene.layout(geometry(false, keyboard = 900))
        repeat(120) {
            run.step(1)
            assertFalse(scene.settingsPanelOut)
        }
        scene.layout(geometry(false))
        repeat(120) {
            run.step(1)
            assertFalse(scene.settingsPanelOut)
        }
    }

    @Test fun openPanelRidesTheField() {
        val run = running(geometry(false))
        val scene = run.scene
        scene.layout(geometry(true))
        run.step(600)
        val dictating = geometry(true, dictating = true)
        scene.layout(dictating)
        run.step(600)
        assertTrue(scene.shape(Part.SETTINGS_PANEL).same(dictating.settingsPanelOpen, 0.25f))
    }

    @Test fun mergingTheDropletDrainsThePanelWithIt() {
        val run = running(geometry(false))
        val scene = run.scene
        scene.layout(geometry(true))
        run.step(600)
        scene.layout(geometry(true, shown = false))
        run.step(600)
        assertFalse(scene.settingsPanelOut)
        assertEquals(0f, scene.settingsFade(), 0.001f)
        assertEquals(0f, scene.settingsFill, 0.001f)
    }

    @Test fun lockingWithTheGearPutAwayDrainsEverythingAndGivesThePadTheScreen() {
        val run = running(geometry(false))
        val scene = run.scene
        scene.layout(geometry(true))
        run.step(600)
        val locked = geometry(false, shown = false, locked = true)
        assertFalse(locked.showField)
        assertEquals(screen.height - 16.5f * locked.unit, locked.pad.bottom, 0.01f)
        scene.layout(locked)
        // Drains once: its spring's rebound must not blink where the droplet was.
        var outs = 0
        var wasOut = scene.settingsPanelOut
        assertTrue(wasOut)
        repeat(600) {
            run.step(1)
            val out = scene.settingsPanelOut
            if (out && !wasOut) outs++
            wasOut = out
        }
        assertEquals(0, outs)
        assertFalse(scene.busy)
        assertFalse(scene.settingsPanelOut)
        assertEquals(0f, scene.settingsFade(), 0.001f)
        assertEquals(0f, scene.fieldFade(), 0.001f)
        assertTrue(scene.shape(Part.PAD).same(locked.pad, 0.25f))
        // Unlocking brings the field back at its full width, without the gear.
        val unlocked = geometry(false, shown = false)
        scene.layout(unlocked)
        run.step(600)
        assertEquals(0f, scene.settingsFade(), 0.001f)
        assertEquals(1f, scene.fieldFade(), 0.001f)
        assertTrue(scene.shape(Part.FIELD).same(unlocked.field, 0.25f))
        assertTrue(scene.shape(Part.FIELD).right > geometry(false).field.right)
    }

    @Test fun reducedMotionOpensAndClosesAtOnce() {
        val run = running(geometry(false), reduced = true)
        val scene = run.scene
        scene.layout(geometry(true))
        assertTrue(scene.settingsPanelOut)
        run.step(1)
        assertEquals(1f, scene.settingsFill, 0.001f)
        scene.layout(geometry(false))
        assertFalse(scene.settingsPanelOut)
        run.step(1)
        assertEquals(0f, scene.settingsFill, 0.001f)
    }
}
