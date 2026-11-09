package com.carriez.flutter_hbb.xr.glass

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InfinityGestureTest {
    private data class Sample(val x: Float, val y: Float, val time: Long)

    private fun infinity(
        cycles: Double = 2.0,
        duration: Long = 1600,
        phase: Double = 0.0,
        direction: Int = 1,
        width: Float = 160f,
        height: Float = 80f,
        rotation: Double = 0.0,
        noise: Float = 0f,
        uneven: Boolean = false,
        steps: Int = 192,
    ): List<Sample> {
        val random = Random(11)
        return (0..steps).map { i ->
            val progress = i.toDouble() / steps
            val t = phase + direction * cycles * 2 * PI * if (uneven) progress * progress else progress
            val x = width * sin(t) / 2
            val y = height * sin(2 * t) / 2
            Sample(
                (240 + x * cos(rotation) - y * sin(rotation)).toFloat() + noise * (random.nextFloat() - 0.5f),
                (360 + x * sin(rotation) + y * cos(rotation)).toFloat() + noise * (random.nextFloat() - 0.5f),
                1000 + duration * i / steps,
            )
        }
    }

    private fun feed(path: List<Sample>, detector: InfinityGesture = InfinityGesture()): Int {
        detector.begin(path.first().x, path.first().y, path.first().time)
        var hits = 0
        for (p in path.drop(1)) if (detector.move(p.x, p.y, p.time)) hits++
        val p = path.last()
        if (detector.end(p.x, p.y, p.time)) hits++
        return hits
    }

    @Test fun recognizesDifferentPhasesDirectionsAndSizes() {
        for (phase in listOf(0.0, 0.31, PI / 2, 2.8, PI, 5.4)) {
            for (direction in listOf(-1, 1)) {
                for (size in listOf(80f to 40f, 160f to 80f, 210f to 70f)) {
                    assertEquals("phase=$phase direction=$direction size=$size", 1,
                        feed(infinity(phase = phase, direction = direction, width = size.first, height = size.second)))
                }
            }
        }
    }

    @Test fun toleratesNoiseTiltAndUnevenSpeed() {
        for (angle in listOf(-0.25, 0.0, 0.25)) {
            assertEquals(1, feed(infinity(rotation = angle, noise = 3f, uneven = true)))
        }
        assertEquals(1, feed(infinity(steps = 48)))
        assertEquals(1, feed(infinity(steps = 480)))
    }

    @Test fun recognizesAsymmetricLobesAndAnIndependentCurve() {
        val asymmetric = infinity().map { p ->
            p.copy(x = 240 + (p.x - 240) * if (p.x < 240) 0.85f else 1.1f)
        }
        assertEquals(1, feed(asymmetric))
        // Bernoulli's lemniscate has a different curvature from the sinusoidal templates.
        val lemniscate = (0..192).map { i ->
            val t = 4 * PI * i / 192
            val denominator = 1 + sin(t) * sin(t)
            Sample((240 + 80 * cos(t) / denominator).toFloat(),
                (360 + 100 * sin(t) * cos(t) / denominator).toFloat(), 1000L + i * 8)
        }
        assertEquals(1, feed(lemniscate))
    }

    @Test fun rejectsOneInfinityFollowedByACircle() {
        val first = infinity(cycles = 1.0, duration = 800)
        val circle = (1..96).map { i ->
            val t = i * 2 * PI / 96
            Sample((240 + 80 * sin(t)).toFloat(), (400 - 40 * cos(t)).toFloat(), 1800L + i * 8)
        }
        assertEquals(0, feed(first + circle))
    }

    @Test fun rejectsIncompleteTracesAndSlowPairs() {
        for (cycles in listOf(0.5, 1.0, 1.5, 1.75)) assertEquals("cycles=$cycles", 0, feed(infinity(cycles = cycles)))
        assertEquals(0, feed(infinity(duration = 5200)))
        assertEquals(1, feed(infinity(duration = 2600)))
        assertEquals(1, feed(infinity(duration = 4000)))
    }

    @Test fun rejectsTinyOrVerticalSymbols() {
        assertEquals(0, feed(infinity(width = 30f, height = 15f)))
        assertEquals(0, feed(infinity(rotation = PI / 2)))
    }

    @Test fun rejectsCirclesLinesZigzagsAndScribbles() {
        val random = Random(9)
        val paths = listOf(
            (0..192).map { i -> Sample(160 + 80 * cos(i * PI / 48).toFloat(), 240 + 40 * sin(i * PI / 48).toFloat(), 1000L + i * 8) },
            (0..192).map { i -> Sample(i.toFloat(), i.toFloat(), 1000L + i * 8) },
            (0..192).map { i -> Sample(i.toFloat(), (i % 24 - 12).toFloat() * 4, 1000L + i * 8) },
            (0..192).map { i -> Sample(160 + random.nextFloat() * 160, 240 + random.nextFloat() * 80, 1000L + i * 8) },
            (0..192).map { i -> Sample(160f, 240f, 1000L + i * 8) },
        )
        for ((i, path) in paths.withIndex()) assertEquals("negative path $i", 0, feed(path))
    }

    @Test fun firesBeforeLiftAndOnlyOnceInALongTouch() {
        val path = infinity(cycles = 4.0, duration = 3200)
        val detector = InfinityGesture()
        detector.begin(path.first().x, path.first().y, path.first().time)
        val hits = path.drop(1).filter { detector.move(it.x, it.y, it.time) }
        assertEquals(1, hits.size)
        assertTrue(hits.single().time < path.last().time)
        assertFalse(detector.end(path.last().x, path.last().y, path.last().time))
    }

    @Test fun separateStrokesAndCancellationNeverCombine() {
        val detector = InfinityGesture()
        assertEquals(0, feed(infinity(cycles = 1.0, duration = 700), detector))
        assertEquals(0, feed(infinity(cycles = 1.0, duration = 700).map { it.copy(time = it.time + 800) }, detector))
        val path = infinity()
        detector.begin(path.first().x, path.first().y, path.first().time)
        for (p in path.take(97)) assertFalse(detector.move(p.x, p.y, p.time))
        detector.cancel()
        for (p in path.drop(97)) assertFalse(detector.move(p.x, p.y, p.time))
        assertEquals(1, feed(infinity(), detector))
    }

    @Test fun findsPairAfterUnrelatedMovementInSameTouch() {
        val detector = InfinityGesture()
        detector.begin(20f, 20f, 0)
        for (i in 1..100) assertFalse(detector.move(20f + i, 20f + i / 2f, i * 10L))
        val path = infinity().map { it.copy(time = it.time + 200) }
        var hits = 0
        for (p in path) if (detector.move(p.x, p.y, p.time)) hits++
        if (detector.end(path.last().x, path.last().y, path.last().time)) hits++
        assertEquals(1, hits)
    }

    @Test fun stationaryPauseCountsTowardsTimeWindow() {
        val path = infinity().mapIndexed { i, p -> if (i > 96) p.copy(time = p.time + 3200) else p }
        assertEquals(0, feed(path))
    }

    @Test fun invalidInputCancelsRecognition() {
        val detector = InfinityGesture()
        detector.begin(100f, 100f, 1000)
        assertFalse(detector.move(Float.NaN, 100f, 1100))
        for (p in infinity()) assertFalse(detector.move(p.x, p.y, p.time))
    }

    private class Sink : TrackpadGesture.Sink {
        var infinities = 0
        var moves = 0
        var clicks = 0
        var drags = 0
        var drops = 0
        override fun pointer(dx: Float, dy: Float, gain: Float, speed: Float, dragging: Boolean) { moves++ }
        override fun click(x: Float, y: Float) { clicks++ }
        override fun dragStart() { drags++ }
        override fun dragEnd() { drops++ }
        override fun secondaryClick(x: Float, y: Float) {}
        override fun scroll(dx: Float, dy: Float, momentum: Boolean) {}
        override fun zoom(factor: Float) {}
        override fun dictate() {}
        override fun isDictating() = false
        override fun stopDictation() {}
        override fun finishDictation(how: DictationFinish) {}
        override fun infinityDouble() { infinities++ }
        override fun settle() {}
    }

    @Test fun trackpadPreservesPointerOutput() {
        val sink = Sink()
        val gesture = TrackpadGesture(sink)
        val path = infinity()
        gesture.begin(path.first().x, path.first().y, 500)
        for (p in path) gesture.update(1, p.x, p.y, 0f, p.time)
        gesture.end(path.last().time)
        assertEquals(1, sink.infinities)
        assertTrue(sink.moves > 0)
        assertEquals(0, sink.drags)
        assertEquals(0, sink.drops)
        assertEquals(0, sink.clicks)
    }

    @Test fun secondFingerDisqualifiesTouchUntilNextDown() {
        val sink = Sink()
        val gesture = TrackpadGesture(sink)
        val path = infinity()
        gesture.begin(path.first().x, path.first().y, path.first().time)
        for ((i, p) in path.withIndex()) {
            gesture.update(if (i == 96) 2 else 1, p.x, p.y, 40f, p.time)
        }
        gesture.end(path.last().time)
        assertEquals(0, sink.infinities)
        val later = path.map { it.copy(time = it.time + 2000) }
        gesture.begin(later.first().x, later.first().y, later.first().time)
        for (p in later) gesture.update(1, p.x, p.y, 0f, p.time)
        gesture.end(later.last().time)
        assertEquals(1, sink.infinities)
    }

    @Test fun batchedSamplesPreserveTheTraceWithoutReplayingPointerEvents() {
        val sink = Sink()
        val gesture = TrackpadGesture(sink)
        val path = infinity()
        gesture.begin(path.first().x, path.first().y, path.first().time)
        for (batch in path.drop(1).chunked(8)) {
            for (p in batch.dropLast(1)) gesture.trace(p.x, p.y, p.time)
            val p = batch.last()
            gesture.update(1, p.x, p.y, 0f, p.time)
        }
        gesture.end(path.last().time)
        assertEquals(1, sink.infinities)
        assertEquals(24, sink.moves)
    }

    @Test fun aClosedBatchedTraceDoesNotAlsoClick() {
        val sink = Sink()
        val gesture = TrackpadGesture(sink)
        val path = infinity(duration = 300)
        gesture.begin(path.first().x, path.first().y, path.first().time)
        for (p in path.drop(1).dropLast(1)) gesture.trace(p.x, p.y, p.time)
        val last = path.last()
        gesture.update(1, last.x, last.y, 0f, last.time)
        gesture.end(last.time)
        assertEquals(1, sink.infinities)
        assertEquals(0, sink.clicks)
    }
}
