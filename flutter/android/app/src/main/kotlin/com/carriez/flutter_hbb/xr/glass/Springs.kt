package com.carriez.flutter_hbb.xr.glass

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** A box in device px, y down. */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    val right get() = x + w
    val bottom get() = y + h

    fun same(other: Box, eps: Float) =
        abs(x - other.x) < eps && abs(y - other.y) < eps && abs(w - other.w) < eps && abs(h - other.h) < eps
}

/** The trackpad's glass: the droplets, the sheets that pour out of them, and the pad. */
enum class Part(val kind: Springs.Kind) {
    BACK(Springs.Kind.DROP),
    SPEED(Springs.Kind.DROP),
    WAKE(Springs.Kind.DROP),
    CENTER(Springs.Kind.DROP),
    LOCK(Springs.Kind.DROP),
    PANEL(Springs.Kind.SHEET),
    FIELD(Springs.Kind.SHEET),
    PAD(Springs.Kind.SHEET),
    // A sheet like the text field it splits from, so the two move as one.
    SETTINGS(Springs.Kind.SHEET),
    // Moves relative to the settings droplet it pours out of (Scene.shape).
    SETTINGS_PANEL(Springs.Kind.SHEET),
}

/**
 * Glass that moves on damped springs, one per edge, as in the Trackpad
 * design, which tuned them after the motion settings of the Liquid Glass
 * Recorder (experiments.thisiswhitespace.com). The edge leading a move is
 * stiffer than the edge trailing it, so glass stretches while it travels and
 * its tail overshoots when it lands. Droplets add a fast, loose jelly spring
 * that trades width for height, so a stretched droplet thins out and a
 * squashed one bulges. [unit] is px per dp of the design.
 */
class Springs(private val unit: Float) {
    enum class Kind(
        val lead: Float,
        val trail: Float,
        val damp: Float,
        val trailDamp: Float,
        val jelly: Float = 0f,
        val jellyDamp: Float = 1f,
        val stretch: Float = 0f,
    ) {
        // Frequencies in Hz, damping as a share of critical.
        DROP(lead = 2.9f, trail = 2.15f, damp = 0.6f, trailDamp = 0.5f, jelly = 5.7f, jellyDamp = 0.34f, stretch = 0.32f),
        SHEET(lead = 2.5f, trail = 1.85f, damp = 0.5f, trailDamp = 0.5f),
    }

    private class Spring(var p: Float, var f: Float, var z: Float) {
        var v = 0f
        var to = p
    }

    private class Body(val kind: Kind, rect: Box) {
        var goal = rect
        var next: Box? = null
        var nextAt = 0L
        val l = Spring(rect.x, kind.lead, kind.damp)
        val r = Spring(rect.right, kind.lead, kind.damp)
        val t = Spring(rect.y, kind.lead, kind.damp)
        val b = Spring(rect.bottom, kind.lead, kind.damp)
        val j = Spring(0f, if (kind.jelly > 0f) kind.jelly else 1f, if (kind.jelly > 0f) kind.jellyDamp else 1f)
        val edges = arrayOf(l, r, t, b)
    }

    private val bodies = arrayOfNulls<Body>(Part.entries.size)
    private var wasOpen: Boolean? = null
    private var impactAt = Long.MAX_VALUE

    /** Still moving, or waiting to. */
    var busy = false
        private set

    /**
     * Sends each part toward its goal after its delay, s. Unlocking and
     * locking ([open] changing) also make the lock reach after the droplets
     * as they leave, and wobble when they land back in it. [still] skips the
     * motion, for reduced motion.
     */
    fun drive(goals: Array<Box>, delays: FloatArray, open: Boolean, now: Long, still: Boolean) {
        var moved = false
        for (part in Part.entries) {
            val rect = goals[part.ordinal]
            val body = bodies[part.ordinal]
            if (body == null || still) {
                if (body == null || body.goal != rect) bodies[part.ordinal] = Body(part.kind, rect)
                continue
            }
            if (body.goal == rect) continue
            body.goal = rect
            body.next = rect
            body.nextAt = now + (delays[part.ordinal] * 1e9f).toLong()
            moved = true
        }
        val previous = wasOpen
        if (moved && previous != null && previous != open) {
            if (open) bodies[Part.LOCK.ordinal]!!.l.v -= LOCK_REACH * unit else impactAt = now + IMPACT_NANOS
        }
        wasOpen = open
        if (moved) busy = true
    }

    /** Steps every spring to [now]. */
    fun step(dt: Float, now: Long) {
        if (now >= impactAt) {
            bodies[Part.LOCK.ordinal]?.let { it.j.v += LOCK_WOBBLE }
            impactAt = Long.MAX_VALUE
            busy = true
        }
        if (!busy) return
        val n = max(1, ceil(dt * 240f).toInt())
        val h = dt / n
        val eps = 0.05f * unit
        var moving = impactAt != Long.MAX_VALUE
        for (body in bodies) {
            if (body == null) continue
            body.next?.let { if (now >= body.nextAt) { aim(body, it); body.next = null } }
            val kind = body.kind
            for (i in 0 until n) {
                for (s in body.edges) pull(s, h)
                if (kind.jelly > 0f) {
                    val rest = body.r.to - body.l.to
                    body.j.to = if (rest > 0f) -kind.stretch * (body.r.p - body.l.p - rest) / rest else 0f
                    pull(body.j, h)
                }
            }
            if (body.next != null || body.edges.any { restless(it, eps) } || restless(body.j, 0.001f)) {
                moving = true
            } else {
                for (s in body.edges) settle(s)
                settle(body.j)
            }
        }
        busy = moving
    }

    /** Where a part is now: its edges, and its height traded for width by the jelly. */
    fun shape(part: Part): Box {
        val b = bodies[part.ordinal]!!
        val w = max(0f, b.r.p - b.l.p)
        val tall = max(0f, b.b.p - b.t.p)
        val h = max(0f, tall * (1f + b.j.p))
        val cy = (b.t.p + b.b.p) / 2f
        return Box(b.l.p, cy - h / 2f, w, h)
    }

    val ready get() = bodies.all { it != null }

    private fun aim(b: Body, rect: Box) {
        val kind = b.kind
        fun axis(lo: Spring, hi: Spring, toLo: Float, toHi: Float) {
            val move = (toLo + toHi - lo.p - hi.p) / 2f
            val grow = abs(toHi - toLo - (hi.p - lo.p))
            // A pure resize has no leading edge, so both ends spring alike.
            val travels = abs(move) > max(2f * unit, grow / 4f)
            for ((s, to, isLo) in listOf(Triple(lo, toLo, true), Triple(hi, toHi, false))) {
                val leads = !travels || (move < 0f) == isLo
                s.to = to
                s.f = if (leads) kind.lead else kind.trail
                s.z = if (leads) kind.damp else kind.trailDamp
            }
        }
        axis(b.l, b.r, rect.x, rect.right)
        axis(b.t, b.b, rect.y, rect.bottom)
    }

    private fun pull(s: Spring, h: Float) {
        val w = 2f * PI.toFloat() * s.f
        s.v += (-w * w * (s.p - s.to) - 2f * s.z * w * s.v) * h
        s.p += s.v * h
    }

    private fun restless(s: Spring, eps: Float) = abs(s.p - s.to) > eps || abs(s.v) > eps

    private fun settle(s: Spring) {
        s.p = s.to
        s.v = 0f
    }

    private companion object {
        // The lock reaching after the droplets, dp/s, and its wobble when
        // they land, in jelly per s, 280 ms after they leave for it.
        const val LOCK_REACH = 240f
        const val LOCK_WOBBLE = 2.4f
        const val IMPACT_NANOS = 280_000_000L
    }
}
