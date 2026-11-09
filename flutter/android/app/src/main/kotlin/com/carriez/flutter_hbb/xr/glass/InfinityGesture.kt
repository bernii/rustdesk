package com.carriez.flutter_hbb.xr.glass

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two complete horizontal infinity traces in one continuous, single-finger touch.
 * Coordinates are dp; times use MotionEvent's monotonic uptime clock.
 *
 * Uses $1's arc-length resampling and Protractor's normalized vector / optimal
 * cosine match (https://depts.washington.edu/acelab/proj/dollar/). Unlike a
 * general classifier, this rejects unmatched input and limits rotation. Ordered
 * double-cycle templates preserve repetition, which a point cloud would lose.
 * Synthetic templates cover starting phase, direction, and several aspect ratios.
 */
class InfinityGesture {
    private data class Point(val x: Float, val y: Float, val time: Long = 0L)

    private val points = ArrayList<Point>()
    private var active = false
    private var fired = false
    private var checkedAt = Long.MIN_VALUE
    private var latestTime = 0L

    fun begin(x: Float, y: Float, time: Long) {
        cancel()
        if (!x.isFinite() || !y.isFinite()) return
        active = true
        latestTime = time
        points.add(Point(x, y, time))
    }

    /** Returns true once per touch, as soon as a complete pair is recognized. */
    fun move(x: Float, y: Float, time: Long, force: Boolean = false): Boolean {
        if (!active || fired) return false
        if (!x.isFinite() || !y.isFinite() || time < latestTime) {
            cancel()
            return false
        }
        latestTime = time
        while (points.isNotEmpty() && time - points.first().time > WINDOW_MS) points.removeAt(0)
        val point = Point(x, y, time)
        val last = points.lastOrNull()
        if (last == null || distance(last, point) >= SAMPLE_DP || (force && distance(last, point) > 0f)) {
            if (points.size == MAX_POINTS) points.removeAt(0)
            points.add(point)
        }
        if (!force && checkedAt != Long.MIN_VALUE && time - checkedAt < CHECK_MS) return false
        checkedAt = time
        // Search suffixes so a normal pointer move before the symbol is harmless.
        // Every candidate is entirely inside the four-second window, including pauses.
        var startAt = Long.MIN_VALUE
        for (start in 0 until points.size - MIN_POINTS + 1) {
            if (startAt != Long.MIN_VALUE && points[start].time - startAt < START_STEP_MS) continue
            startAt = points[start].time
            if (matches(points.subList(start, points.size))) {
                fired = true
                points.clear()
                return true
            }
        }
        return false
    }

    /** A lift ends the sequence; two separate drawings never combine. */
    fun end(x: Float, y: Float, time: Long): Boolean {
        val recognized = move(x, y, time, force = true)
        cancel()
        return recognized
    }

    /** Also used for a second finger, Android cancellation, and lifecycle changes. */
    fun cancel() {
        points.clear()
        active = false
        fired = false
        checkedAt = Long.MIN_VALUE
    }

    private fun matches(path: List<Point>): Boolean {
        if (path.size < MIN_POINTS) return false
        var minX = path.first().x
        var maxX = minX
        var minY = path.first().y
        var maxY = minY
        var length = 0f
        path.forEachIndexed { i, p ->
            minX = minOf(minX, p.x)
            maxX = maxOf(maxX, p.x)
            minY = minOf(minY, p.y)
            maxY = maxOf(maxY, p.y)
            if (i > 0) length += distance(path[i - 1], p)
        }
        val width = maxX - minX
        val height = maxY - minY
        if (width < MIN_WIDTH_DP || height < MIN_HEIGHT_DP || width / height !in 1.2f..4f) return false
        if (distance(path.first(), path.last()) > width * CLOSURE_RATIO) return false
        if (length / width !in 4.3f..8.5f) return false
        val vector = vectorize(resample(path, length))
        for (template in templates) {
            var dot = 0f
            var cross = 0f
            for (i in vector.indices step 2) {
                dot += template[i] * vector[i] + template[i + 1] * vector[i + 1]
                cross += template[i] * vector[i + 1] - template[i + 1] * vector[i]
            }
            // Protractor's optimal rotation, constrained to a sideways infinity.
            if (sqrt(dot * dot + cross * cross) >= MIN_SIMILARITY &&
                abs(atan2(cross, dot)) <= MAX_ROTATION) return true
        }
        return false
    }

    private companion object {
        const val WINDOW_MS = 4000L
        const val CHECK_MS = 32L
        const val START_STEP_MS = 32L
        const val SAMPLE_DP = 1.5f
        const val MAX_POINTS = 512
        const val MIN_POINTS = 24
        const val MIN_WIDTH_DP = 48f
        const val MIN_HEIGHT_DP = 24f
        const val CLOSURE_RATIO = 0.12f
        const val MIN_SIMILARITY = 0.985f
        val MAX_ROTATION = (25.0 * PI / 180.0).toFloat()
        const val RESAMPLED_POINTS = 96

        fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

        /** Equal distance samples keep matching independent of drawing speed/event rate. */
        fun resample(path: List<Point>, length: Float): List<Point> {
            val result = ArrayList<Point>(RESAMPLED_POINTS)
            var segment = 1
            var before = 0f
            var segmentLength = distance(path[0], path[1])
            for (i in 0 until RESAMPLED_POINTS) {
                val target = length * i / (RESAMPLED_POINTS - 1)
                while (segment < path.lastIndex && before + segmentLength < target) {
                    before += segmentLength
                    segment++
                    segmentLength = distance(path[segment - 1], path[segment])
                }
                val a = path[segment - 1]
                val b = path[segment]
                val fraction = if (segmentLength > 0f) ((target - before) / segmentLength).coerceIn(0f, 1f) else 0f
                result.add(Point(a.x + fraction * (b.x - a.x), a.y + fraction * (b.y - a.y)))
            }
            return result
        }

        fun vectorize(path: List<Point>): FloatArray {
            val cx = path.sumOf { it.x.toDouble() }.toFloat() / path.size
            val cy = path.sumOf { it.y.toDouble() }.toFloat() / path.size
            val vector = FloatArray(path.size * 2)
            var magnitude = 0f
            path.forEachIndexed { i, p ->
                vector[2 * i] = p.x - cx
                vector[2 * i + 1] = p.y - cy
                magnitude += (p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy)
            }
            val norm = sqrt(magnitude)
            for (i in vector.indices) vector[i] /= norm
            return vector
        }

        // Initialized once, away from the per-event matching loop.
        val templates: List<FloatArray> = buildList {
            for (aspect in listOf(1.5f, 2f, 2.5f, 3f, 3.5f)) {
                for (direction in listOf(-1, 1)) {
                    for (phase in 0 until 16) {
                        val path = (0..512).map { i ->
                            val t = phase * 2 * PI / 16 + direction * i * 4 * PI / 512
                            Point(sin(t).toFloat() * aspect, sin(2 * t).toFloat())
                        }
                        val length = path.zipWithNext { a, b -> distance(a, b) }.sum()
                        add(vectorize(resample(path, length)))
                    }
                }
            }
        }
    }
}
