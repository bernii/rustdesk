package com.carriez.flutter_hbb.xr.glass

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The G-force: the phone's motion as the glass's oil feels it, as in the web
 * playground (main.js's updateGForce, gforceUniforms and edgeUniforms; the
 * README's G-force). On a phone turning at ω and speeding its turn up at α, a
 * point r from the screen's center feels f + J r: what the accelerometer
 * reports, plus the turn's Euler and centrifugal terms, from the gyroscope.
 * Along the screen it pushes the oil; into it, it's the oil's weight.
 *
 * Each frame on the GL thread: [update], then [setOnStep] and [setOnGlass].
 * [heaviest] sizes the simulation's time step.
 */
class GForce(private val params: GForceParams, private val density: Float) {
    // The force a held pose settles to: push x, y (screen axes, y down) and
    // weight, m/s². It starts at however the phone is held.
    private val level = FloatArray(3)
    private var levelReady = false
    // After the level and the dead zone, m/s²: the push, its slow part (a
    // QUICK_TIME average) and the weight at the center, and how the push and
    // the weight change per px.
    private val push = FloatArray(2)
    private val slow = FloatArray(2)
    private var weight = 0f
    private val field = FloatArray(4)
    private val slope = FloatArray(2)

    private var width = 0f
    private var height = 0f
    private var oilHalfW = 0f
    private var oilHalfH = 0f
    private var thickness = 0f

    /** The push the phone feels after the level drift, m/s². */
    val felt get() = hypot(push[0], push[1])

    /** The phone's motion moves the oil this frame. */
    var active = false
        private set

    /** The heaviest the oil is anywhere this frame, relative to rest. */
    var heaviest = 1f
        private set

    /** The screen and the oil's half extents, px, and its plateau thickness, px. */
    fun setShape(screenW: Int, screenH: Int, halfW: Float, halfH: Float, thicknessPx: Float) {
        width = screenW.toFloat()
        height = screenH.toFloat()
        oilHalfW = halfW
        oilHalfH = halfH
        thickness = thicknessPx
    }

    fun update(dt: Float, motion: DeviceMotion) {
        if (!params.enabled || !motion.ready || dt <= 0f) return
        val reading = floatArrayOf(motion.forceX, motion.forceY, motion.weight)
        if (!levelReady) {
            reading.copyInto(level)
            levelReady = true
        }
        val follow = min(1f, dt / params.levelTime)
        for (i in 0 until 3) level[i] += (reading[i] - level[i]) * follow
        push[0] = reading[0] - level[0]
        push[1] = reading[1] - level[1]
        shrink(push, 1f)
        val center = floatArrayOf(reading[2] - level[2])
        shrink(center, 1f)
        weight = center[0]
        val average = min(1f, dt / QUICK_TIME)
        for (i in 0 until 2) slow[i] += (push[i] - slow[i]) * average

        // How the force changes per metre in the device's axes (y up, z out
        // of the screen), −[α]× − [ω]×[ω]×: its columns for x and for y.
        val (wx, wy, wz) = motion.spin
        val (ax, ay, az) = motion.spinUp
        val w2 = wx * wx + wy * wy + wz * wz
        val jx = floatArrayOf(w2 - wx * wx, -az - wy * wx, ay - wz * wx)
        val jy = floatArrayOf(az - wx * wy, w2 - wy * wy, -ax - wz * wy)
        // Per px in the screen's axes, y down; the weight is minus z. The dead
        // zone applies as it would at the screen's corners.
        val metresPerPx = 0.0254f / (160f * density)
        val corner = hypot(width / 2f, height / 2f)
        floatArrayOf(jx[0], -jy[0], -jx[1], jy[1]).forEachIndexed { i, v -> field[i] = v * metresPerPx }
        floatArrayOf(-jx[2], jy[2]).forEachIndexed { i, v -> slope[i] = v * metresPerPx }
        shrink(field, corner)
        shrink(slope, corner)

        val load = params.load / G
        heaviest = min(1f + abs(load * weight) + hypot(slope[0], slope[1]) * load * corner, WEIGHT_MAX)
        active = felt > 0f || hypot(slow[0], slow[1]) > 0.01f || weight != 0f || field.any { it != 0f } || slope.any { it != 0f }
    }

    /** sim_step.frag's G-force, with c the wave speed, cells/s. */
    fun setOnStep(step: Program, c: Float) {
        val cell = GlassRenderer.CELL
        step.set("uCenter", width / 2f / cell, height / 2f / cell)
        if (!params.enabled) return
        // As gforceUniforms(): gains relative to real oil's tilt, through the
        // simulation's own gravity, held short of piling the oil past the clip.
        val toSim = c * c / (thickness / cell) / G
        val halfW = oilHalfW / cell
        val halfH = oilHalfH / cell
        val steady = floatArrayOf(STEADY_PILE * c * c / halfW, STEADY_PILE * c * c / halfH)
        val quick = QUICK_PILE * c / QUICK_TIME
        val pushed = FloatArray(2) {
            val held = (slow[it] * params.push * toSim).coerceIn(-steady[it], steady[it])
            val fast = ((push[it] - slow[it]) * params.push * toSim).coerceIn(-quick, quick)
            held + fast
        }
        // Grid axes have y up.
        step.set("uPush", pushed[0], -pushed[1])
        val k = params.spin * toSim * cell
        val spin = floatArrayOf(field[0] * k, -field[1] * k, -field[2] * k, field[3] * k)
        val spinMax = hypot(hypot(spin[0], spin[1]), hypot(spin[2], spin[3])) * hypot(halfW, halfH)
        if (spinMax > quick) for (i in 0 until 4) spin[i] *= quick / spinMax
        step.set("uSpin", spin[0], spin[1], spin[2], spin[3])
        val load = params.load / G
        step.set("uWeight", load * weight, load * slope[0] * cell, -load * slope[1] * cell)
    }

    /** glass.frag's weight, per px with y down, and the edge. */
    fun setOnGlass(glass: Program) {
        if (!params.enabled) return
        val load = params.load / G
        glass.set("uWeight", load * weight, load * slope[0], load * slope[1])
        glass.set("uEdgeFollow", min(params.edge, 1f))
        glass.set("uEdgeGain", max(params.edge, 1f))
        glass.set("uEdgeReach", GlassRenderer.EDGE_BAND_DP * density)
    }

    /** The oil's weight from the lightest corner to the heaviest, %, as the gains leave it. */
    fun weightRange(): Pair<Int, Int> {
        val load = params.load / G
        val w = load * weight
        val reach = abs(load * slope[0]) * width / 2f + abs(load * slope[1]) * height / 2f
        fun percent(v: Float) = Math.round(100f * (1f + v).coerceIn(WEIGHT_MIN, WEIGHT_MAX))
        return percent(w - reach) to percent(w + reach)
    }

    // Shrinks v toward zero by DEAD, at `scale` m/s² per unit, keeping its
    // direction: a hand's tremor and the sensors' noise move nothing.
    private fun shrink(v: FloatArray, scale: Float) {
        var sum = 0f
        for (x in v) sum += x * x
        val length = sqrt(sum) * scale
        val k = if (length > DEAD) (length - DEAD) / length else 0f
        for (i in v.indices) v[i] *= k
    }

    private companion object {
        const val G = 9.81f
        const val DEAD = 0.3f // m/s²
        // main.js's gforceUniforms(), and sim_step.frag's weight limits.
        const val STEADY_PILE = 0.8f
        const val QUICK_PILE = 1.6f
        const val QUICK_TIME = 0.5f // s
        const val WEIGHT_MIN = 0.1f
        const val WEIGHT_MAX = 2.5f
    }
}
