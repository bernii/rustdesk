package com.carriez.flutter_hbb.xr.glass

import org.junit.Assert.*
import org.junit.Test

class SettingsRevealTest {
    private val screen = Screen(1080, 2424, 2.625f, 84, 72, 0)
    private fun geometry(shown: Boolean, locked: Boolean = false, keyboard: Int = 0, dictating: Boolean = false) =
        Geometry(screen.copy(keyboard = keyboard), Modes(locked, false, dictating, shown))

    @Test fun revealedGearFitsBesideInputWithAndWithoutKeyboard() {
        for (locked in listOf(false, true)) for (keyboard in listOf(0, 900)) for (dictating in listOf(false, true)) {
            val geo = geometry(true, locked, keyboard, dictating)
            assertTrue(geo.showField)
            assertEquals(10f * geo.unit, geo.settingsHome.x - geo.field.right, 0.01f)
            assertTrue(geo.settingsHome.right < screen.width)
            assertTrue(geo.settingsHome.bottom <= screen.height - keyboard)
            assertTrue(geo.settingsHome.w / screen.density >= 48f)
            assertTrue(geo.pad.bottom < geo.field.y)
        }
    }

    @Test fun revealAndMergeSettleAndCanReverseMidAnimation() {
        val scene = Scene(screen.density, false)
        var time = 0L
        fun step(frames: Int) {
            repeat(frames) { time += 8_333_333L; scene.advance(time, 1f / 120) }
        }
        scene.layout(geometry(false))
        assertEquals(0f, scene.settingsFade(), 0.001f)
        scene.layout(geometry(true))
        step(20)
        assertTrue(scene.busy)
        scene.layout(geometry(false))
        step(600)
        assertEquals(0f, scene.settingsFade(), 0.001f)
        assertTrue(scene.shape(Part.FIELD).same(geometry(false).field, 0.25f))
        scene.layout(geometry(true))
        step(600)
        assertEquals(1f, scene.settingsFade(), 0.001f)
        assertEquals(1f, scene.fieldFade(), 0.001f)
        assertTrue(scene.shape(Part.SETTINGS).same(geometry(true).settingsHome, 0.25f))
    }

    @Test fun reducedMotionRevealsAndHidesImmediately() {
        val scene = Scene(screen.density, true)
        scene.layout(geometry(false))
        scene.layout(geometry(true))
        assertEquals(1f, scene.settingsFade(), 0.001f)
        scene.layout(geometry(false))
        assertEquals(0f, scene.settingsFade(), 0.001f)
    }

    private fun taps() = DoubleTapDismiss(8f, 40f, 300)

    @Test fun hidesOnlyOnSecondCompletedTap() {
        val taps = taps()
        taps.begin(100f, 100f, 0)
        assertFalse(taps.end(100f, 100f, 60))
        taps.begin(102f, 101f, 150)
        assertTrue(taps.end(102f, 101f, 210))
        assertFalse(taps.end(102f, 101f, 220))
    }

    @Test fun dragHoldDistantAndLateTapsDoNotDismiss() {
        for (scenario in 0..3) {
            val taps = taps()
            taps.begin(100f, 100f, 0)
            assertFalse(taps.end(100f, 100f, 50))
            val x = if (scenario == 2) 200f else 100f
            taps.begin(x, 100f, if (scenario == 3) 600 else 150)
            if (scenario == 0) { taps.move(130f, 100f); taps.move(100f, 100f) }
            assertFalse(taps.end(x, 100f, if (scenario >= 1 && scenario != 2) 650 else 210))
        }
    }

    @Test fun cancellationAndUnpairedLiftDoNotDismiss() {
        val taps = taps()
        assertFalse(taps.end(100f, 100f, 0))
        taps.begin(100f, 100f, 10)
        taps.end(100f, 100f, 60)
        taps.begin(100f, 100f, 150)
        taps.cancel()
        assertFalse(taps.end(100f, 100f, 200))
    }
}
