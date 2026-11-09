package com.carriez.flutter_hbb.xr.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** RustDesk XR's input contract, as flutter/test/xr_gestures_test.dart pinned it before the recognizer moved here. */
class TrackpadGestureTest {
    private class Log : TrackpadGesture.Sink {
        val events = ArrayList<String>()
        val finishes = ArrayList<DictationFinish>()
        val moves = ArrayList<Float>()
        var active = false
        var starts = 0
        var stops = 0
        override fun pointer(dx: Float, dy: Float, gain: Float, speed: Float, dragging: Boolean) {
            moves += dx * gain
            events += "move"
        }
        override fun click(x: Float, y: Float) { events += "click" }
        override fun dragStart() { events += "down" }
        override fun dragEnd() { events += "up" }
        override fun secondaryClick(x: Float, y: Float) { events += "right" }
        override fun scroll(dx: Float, dy: Float, momentum: Boolean) { events += "scroll" }
        override fun zoom(factor: Float) { events += "zoom" }
        override fun dictate() {
            active = true
            starts++
        }
        override fun isDictating() = active
        override fun stopDictation() { stops++ }
        override fun finishDictation(how: DictationFinish) { finishes += how }
        override fun infinityDouble() {}
        override fun settle() {}
    }

    private fun TrackpadGesture.twoFingerTap(at: Long) {
        begin(0f, 0f, at)
        update(2, 0f, 0f, 30f, at + 10)
        end(at + 70)
    }

    @Test fun twoFingerDoubleTapDictatesWithoutASecondaryClick() {
        val log = Log()
        val gesture = TrackpadGesture(log)
        gesture.twoFingerTap(0)
        gesture.twoFingerTap(180)
        gesture.flushSecondaryTap(1000)
        assertEquals(1, log.starts)
        assertEquals(emptyList<String>(), log.events)
        log.active = false
        gesture.twoFingerTap(1200)
        gesture.flushSecondaryTap(1570)
        assertEquals(listOf("right"), log.events)
    }

    @Test fun holdsAndTapThenMoveNeverDragAndGainUsesSmoothedSpeed() {
        val log = Log()
        val gesture = TrackpadGesture(log)
        gesture.begin(0f, 0f, 0)
        gesture.update(1, 8f, 0f, 0f, 450)
        gesture.cancel()
        assertEquals(listOf("move"), log.events)
        assertEquals(2f, log.moves.single(), 1e-5f)
        assertEquals(3f, TrackpadGesture.gain(1.5f), 1e-5f)

        log.events.clear()
        gesture.begin(0f, 0f, 1000)
        gesture.end(1060)
        gesture.begin(0f, 0f, 1100)
        gesture.update(1, 8f, 0f, 0f, 1120)
        gesture.end(1150)
        assertEquals("The first tap clicks; the second touch only moves.", listOf("click", "move"), log.events)

        gesture.begin(0f, 0f, 2000)
        gesture.update(1, 10f, 0f, 0f, 2100)
        gesture.update(1, 20f, 0f, 0f, 2120)
        assertEquals(10f * TrackpadGesture.gain(0.1f * 0.6f + 0.5f * 0.4f), log.moves.last(), 1e-4f)
    }

    @Test fun thirdTwoFingerTouchStopsThenReleaseOrPinchChoosesExactlyOnce() {
        for (how in DictationFinish.entries) {
            val log = Log()
            val gesture = TrackpadGesture(log)
            for (time in listOf(0L, 180L)) {
                gesture.begin(0f, 0f, time)
                gesture.update(2, 0f, 0f, 100f, time + 10)
                gesture.end(time + 70)
            }
            assertEquals(1, log.starts)

            gesture.begin(0f, 0f, 1000)
            gesture.update(2, 0f, 0f, 100f, 1010)
            assertEquals(1, log.stops)
            assertEquals(emptyList<DictationFinish>(), log.finishes)
            val span = when (how) {
                DictationFinish.ENTER -> 70f
                DictationFinish.DISCARD -> 130f
                DictationFinish.SEND -> 100f
            }
            gesture.update(2, 20f, 0f, span, 1100)
            gesture.update(2, 40f, 0f, span, 1200)
            gesture.update(1, 0f, 0f, 0f, 1500)
            assertFalse(gesture.end(1510))
            gesture.flushSecondaryTap(2000)
            assertEquals(listOf(how), log.finishes)
            assertEquals(emptyList<String>(), log.events)
        }
    }

    @Test fun threeFingerMovementDragsAndLiftingAFingerDropsWithoutAClick() {
        val log = Log()
        val gesture = TrackpadGesture(log)
        gesture.begin(0f, 0f, 0)
        gesture.update(2, 20f, 0f, 40f, 10)
        gesture.update(3, 40f, 0f, 0f, 20)
        gesture.update(3, 43f, 0f, 0f, 30)
        assertEquals(emptyList<String>(), log.events)
        gesture.update(3, 48f, 0f, 0f, 40)
        gesture.update(3, 58f, 0f, 0f, 50)
        assertEquals(listOf("down", "move", "move"), log.events)
        gesture.update(2, 80f, 0f, 40f, 60)
        gesture.update(2, 100f, 0f, 40f, 70)
        gesture.update(1, 120f, 0f, 0f, 80)
        gesture.update(1, 140f, 0f, 0f, 90)
        assertFalse(gesture.end(100))
        gesture.flushSecondaryTap(1000)
        assertEquals(listOf("down", "move", "move", "up"), log.events)
    }

    @Test fun dragReleasesOnCancelOrEndAndNeverCarriesScrollMomentum() {
        for (cancel in listOf(false, true)) {
            val log = Log()
            val gesture = TrackpadGesture(log)
            gesture.begin(0f, 0f, 0)
            gesture.update(2, 0f, 0f, 40f, 10)
            gesture.update(2, 20f, 0f, 40f, 20)
            log.events.clear()
            gesture.update(3, 0f, 0f, 0f, 30)
            gesture.update(3, 10f, 0f, 0f, 40)
            if (cancel) gesture.cancel()
            assertFalse(gesture.end(50))
            gesture.cancel()
            assertEquals(listOf("down", "move", "up"), log.events)
            assertEquals(0f, gesture.velocityX, 0f)
            assertEquals(0f, gesture.velocityY, 0f)
            log.events.clear()
            gesture.begin(0f, 0f, 1000)
            gesture.update(3, 0f, 0f, 0f, 1010)
            gesture.end(1020)
            gesture.flushSecondaryTap(2000)
            assertEquals(emptyList<String>(), log.events)
        }
    }
}
