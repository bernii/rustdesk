package com.carriez.flutter_hbb.xr.glass

import android.opengl.GLES30.GL_RGBA16F
import android.opengl.GLES30.GL_RGBA32F
import android.opengl.GLES30.GL_RGBA8
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** marble.glsl's uniforms, shared by every program that draws the marble. */
fun Program.setMarble(bg: BackgroundParams, density: Float) {
    set("uDensity", density)
    setUint("uSeed", bg.seed)
    set("uSaturation", bg.saturation)
    set("uHue", Math.toRadians(bg.hue.toDouble()).toFloat())
    set("uHueRange", bg.hueRange)
    set("uWarpScale", bg.warpScale)
    set("uWarpFrequency", bg.warpFrequency)
    setInt("uWarpOctaves", bg.warpOctaves)
    set("uBaseFrequency", bg.baseFrequency)
    setInt("uBaseOctaves", bg.baseOctaves)
    set("uSlope", bg.slope)
    set("uOffset", bg.offset)
    set("uGrainOpacity", bg.grainOpacity)
    set("uGrainFrequency", bg.grainFrequency)
}

/**
 * The moving marble: two oils, white and black, that keep trying to mix and
 * can't. The same passes as the web playground's stepFlow() and render():
 * once a frame where each oil is gathered and how much of each there is,
 * the oils' velocity (momentum, currents, the device's push and a shake's
 * churn, their attraction to their own kind, friction) and a pressure solve
 * that keeps them incompressible, then Cahn–Hilliard substeps that carry them
 * along and pull them apart, then how far they drifted for the fibres, then
 * the marble drawn from where they are.
 *
 * [build] compiles a program from the named shader assets. [blur] runs a
 * separable blur from a texture, through a scratch target, into a target of
 * the same size, σ in its texels.
 */
class MarbleFlow(
    private val bg: BackgroundParams,
    private val density: Float,
    build: (name: String, chunks: List<String>) -> Program,
    private val blur: (source: Int, scratch: Target, into: Target, sigma: Float) -> Unit,
) {
    private val source = build("marble_source", listOf("common.glsl", "marble.glsl", "marble_source.frag"))
    private val init = build("flow_init", listOf("common.glsl", "flow.glsl", "flow_init.frag"))
    private val force = build("flow_force", listOf("common.glsl", "flow.glsl", "flow_force.frag"))
    private val divergence = build("flow_divergence", listOf("common.glsl", "flow.glsl", "flow_divergence.frag"))
    private val pressure = build("flow_pressure", listOf("common.glsl", "flow_pressure.frag"))
    private val project = build("flow_project", listOf("common.glsl", "flow.glsl", "flow_project.frag"))
    private val step = build("flow_step", listOf("common.glsl", "flow.glsl", "flow_step.frag"))
    private val display = build("flow_display", listOf("common.glsl", "flow.glsl", "flow_display.frag"))
    private val drift = build("flow_drift", listOf("common.glsl", "flow.glsl", "flow_drift.frag"))
    private val gather = build("flow_gather", listOf("common.glsl", "flow.glsl", "flow_gather.frag"))
    private val measure = build("flow_measure", listOf("common.glsl", "flow_measure.frag"))
    private val motion = build("marble_motion", listOf("common.glsl", "marble.glsl", "flow.glsl", "marble_motion.frag"))

    private lateinit var sourceTarget: Target
    private lateinit var lowTargets: Array<Target>
    private lateinit var flowTargets: Array<Target>
    private lateinit var divergenceTarget: Target
    private lateinit var pressureTargets: Array<Target>
    private lateinit var displayTarget: Target
    private lateinit var driftTargets: Array<Target>
    private lateinit var gatheredTarget: Target
    private lateinit var nearTargets: Array<Target>
    private lateinit var farTarget: Target
    private lateinit var measureTarget: Target
    private lateinit var baselineTarget: Target

    private var time = 0f
    private var mobility = 0f
    private var stepDtMax = 0f
    private val levelForce = FloatArray(2)
    private var levelReady = false
    private val smoothPull = FloatArray(2)
    private var shakeChurn = 0f
    private var churnTurns = 0f

    /** The body force the oils felt last frame, m/s², after the level drift. */
    val pull = FloatArray(2)

    /** How fast a shake churns the oils, dp/s. */
    val churn get() = bg.shakeChurn * max(0f, shakeChurn - SHAKE_FLOOR)

    /** Allocates the marble's targets for a w × h screen; [target] registers each. */
    fun resize(w: Int, h: Int, target: (Int, Int, Int) -> Target) {
        val flowW = ceil(w / FLOW_CELL.toFloat()).toInt()
        val flowH = ceil(h / FLOW_CELL.toFloat()).toInt()
        sourceTarget = target(w, h, GL_RGBA8)
        lowTargets = arrayOf(target(w / 2, h / 2, GL_RGBA8), target(w / 2, h / 2, GL_RGBA8))
        flowTargets = arrayOf(target(flowW, flowH, GL_RGBA32F), target(flowW, flowH, GL_RGBA32F))
        divergenceTarget = target(flowW, flowH, GL_RGBA32F)
        pressureTargets = arrayOf(target(flowW, flowH, GL_RGBA32F), target(flowW, flowH, GL_RGBA32F))
        displayTarget = target(flowW, flowH, GL_RGBA16F)
        driftTargets = arrayOf(target(flowW, flowH, GL_RGBA16F), target(flowW, flowH, GL_RGBA16F))
        val blocksW = ceil(flowW / GATHER.toFloat()).toInt()
        val blocksH = ceil(flowH / GATHER.toFloat()).toInt()
        gatheredTarget = target(blocksW, blocksH, GL_RGBA16F)
        nearTargets = arrayOf(target(blocksW, blocksH, GL_RGBA16F), target(blocksW, blocksH, GL_RGBA16F))
        farTarget = target(blocksW, blocksH, GL_RGBA16F)
        measureTarget = target(1, 1, GL_RGBA32F)
        baselineTarget = target(1, 1, GL_RGBA32F)
        setStaticUniforms(w, h)
    }

    private fun setStaticUniforms(w: Int, h: Int) {
        val toCells = density / FLOW_CELL
        // Mobility is set so the fastest-growing separation has rate
        // `tension`. The explicit limits are the biharmonic (32 M W²) and the
        // double well (16 M).
        val width2 = FLOW_WIDTH * FLOW_WIDTH
        mobility = 4f * width2 * bg.tension
        stepDtMax = if (mobility > 0f) 0.8f * min(1f / (32f * mobility * width2), 1f / (16f * mobility)) else 1f / 30f

        val slope = max(bg.slope, 0.05f)
        fun Program.greys() {
            set("uGreyWhite", (1.05f - bg.offset) / slope)
            set("uGreyBlack", (0.4f - bg.offset) / slope)
            set("uGreySplit", (0.85f - bg.offset) / slope)
            set("uFlowCell", FLOW_CELL.toFloat())
        }

        source.use()
        source.setMarble(bg, density)
        source.set("uRes", w.toFloat(), h.toFloat())

        init.use()
        init.greys()
        init.setInt("uSourceLow", 1)
        init.set("uRes", w.toFloat(), h.toFloat())

        force.use()
        force.greys()
        force.setInt("uFlow", 0)
        force.set("uSpeed", bg.flowSpeed * toCells)
        force.set("uCounterflow", bg.counterflow)
        force.set("uSwirl", bg.swirlSize * toCells)
        force.set("uChange", bg.change)
        force.set("uFriction", bg.friction)
        force.set("uChurnSize", bg.churnSize * toCells)
        force.setInt("uNear", 1)
        force.setInt("uFar", 2)
        force.set("uAttraction", bg.attraction * toCells)
        force.set("uAttractionRange", bg.attractionRange * toCells)
        force.set("uCrowding", bg.crowding)
        force.setUint("uSeed", bg.seed)

        divergence.use()
        divergence.greys()
        divergence.setInt("uFlow", 0)

        pressure.use()
        pressure.setInt("uPressure", 0)
        pressure.setInt("uDivergence", 1)

        project.use()
        project.greys()
        project.setInt("uFlow", 0)
        project.setInt("uPressure", 1)

        step.use()
        step.greys()
        step.setInt("uFlow", 0)
        step.set("uMobility", mobility)
        step.set("uWidth2", width2)
        step.setInt("uMeasure", 1)
        step.setInt("uBaseline", 2)
        step.set("uSharpen", bg.tension)
        step.set("uRestore", RESTORE_RATE)

        gather.use()
        gather.setInt("uFlow", 0)
        measure.use()
        measure.setInt("uGathered", 0)
        measure.setInt("uCells", ceil(w / FLOW_CELL.toFloat()).toInt(), ceil(h / FLOW_CELL.toFloat()).toInt())

        drift.use()
        drift.setInt("uFlow", 0)
        drift.setInt("uDrift", 1)

        display.use()
        display.greys()
        display.setInt("uFlow", 0)
        // The boundary's visible width is about 2√2 σ.
        display.set("uSoftness", bg.interfaceWidth * toCells / 2.83f)

        motion.use()
        motion.setMarble(bg, density)
        motion.greys()
        motion.setInt("uFlowDisplay", 0)
        motion.setInt("uSource", 1)
        motion.setInt("uSourceLow", 2)
        motion.setInt("uDrift", 3)
        motion.set("uRes", w.toFloat(), h.toFloat())
        motion.set("uDetail", bg.detail)
    }

    /** The still marble's ingredients and their broad grey, and the oils started from that grey. */
    fun prepare() {
        source.use()
        sourceTarget.bind()
        drawFullscreen()
        blur(sourceTarget.texture, lowTargets[1], lowTargets[0], MARBLE_LOW_DP * density / 4f)

        init.use()
        bindTexture(1, lowTargets[0].texture)
        flowTargets[0].bind()
        drawFullscreen()
        pressureTargets.forEach { it.clear() }
        driftTargets[0].clear()
        measureInto(baselineTarget)
        time = 0f
        churnTurns = 0f
        levelReady = false
    }

    /**
     * One frame of the oils. ([forceX], [forceY]) is the device's body force,
     * m/s² in screen axes; a held tilt drifts into the new level. A shake is
     * the force's quick part, what a fifth of a second's average misses: it
     * churns the oils, rising within a swing or two and dying away after.
     */
    fun step(frameDt: Float, forceX: Float, forceY: Float) {
        if (!levelReady) {
            levelForce[0] = forceX
            levelForce[1] = forceY
            smoothPull.fill(0f)
            shakeChurn = 0f
            levelReady = true
        }
        val follow = min(1f, frameDt / bg.levelTime)
        levelForce[0] += (forceX - levelForce[0]) * follow
        levelForce[1] += (forceY - levelForce[1]) * follow
        pull[0] = forceX - levelForce[0]
        pull[1] = forceY - levelForce[1]

        val smoothFollow = min(1f, frameDt / SHAKE_SMOOTH)
        smoothPull[0] += (pull[0] - smoothPull[0]) * smoothFollow
        smoothPull[1] += (pull[1] - smoothPull[1]) * smoothFollow
        val shake = hypot(pull[0] - smoothPull[0], pull[1] - smoothPull[1])
        shakeChurn = if (shake > shakeChurn) {
            shakeChurn + (shake - shakeChurn) * min(1f, frameDt / CHURN_RISE)
        } else {
            shakeChurn * exp(-frameDt / bg.churnTime)
        }
        val churnSpeed = churn
        churnTurns += frameDt * churnSpeed * CHURN_SHIFT / bg.churnSize

        // Where each oil is gathered, for their attraction to their own kind,
        // and how much of each there is now.
        measureInto(measureTarget)
        val nearSigma = bg.attractionRange * density / FLOW_CELL / GATHER / 2f
        blur(gatheredTarget.texture, nearTargets[0], nearTargets[1], nearSigma)
        blur(gatheredTarget.texture, nearTargets[0], farTarget, CROWDING_REACH * nearSigma)

        // The white oil's push per unit phase, cells/s² per m/s² of force.
        val push = bg.motionResponse * density / FLOW_CELL * (bg.density / 2f)
        force.use()
        force.set("uDt", frameDt)
        force.set("uTime", time)
        force.set("uBuoyancy", pull[0] * push, -pull[1] * push)
        force.set("uChurn", churnSpeed * density / FLOW_CELL)
        force.set("uChurnTurns", churnTurns)
        flowTargets[1].bind()
        bindTexture(0, flowTargets[0].texture)
        bindTexture(1, nearTargets[1].texture)
        bindTexture(2, farTarget.texture)
        drawFullscreen()
        flowTargets.swap()

        divergence.use()
        divergenceTarget.bind()
        bindTexture(0, flowTargets[0].texture)
        drawFullscreen()
        pressure.use()
        bindTexture(1, divergenceTarget.texture)
        repeat(PRESSURE_ITERATIONS) {
            pressureTargets[1].bind()
            bindTexture(0, pressureTargets[0].texture)
            drawFullscreen()
            pressureTargets.swap()
        }
        project.use()
        bindTexture(1, pressureTargets[0].texture)
        flowTargets[1].bind()
        bindTexture(0, flowTargets[0].texture)
        drawFullscreen()
        flowTargets.swap()

        val steps = min(max(1, ceil(frameDt / stepDtMax).toInt()), MAX_FLOW_STEPS)
        val dt = min(frameDt / steps, stepDtMax)
        step.use()
        step.set("uDt", dt)
        bindTexture(1, measureTarget.texture)
        bindTexture(2, baselineTarget.texture)
        repeat(steps) { i ->
            step.set("uCarry", if (i == 0) steps * dt else 0f)
            flowTargets[1].bind()
            bindTexture(0, flowTargets[0].texture)
            drawFullscreen()
            flowTargets.swap()
        }

        // How far the oils drifted, each copy of the fibres restarting on
        // marble_motion.frag's beat.
        fun beat(t: Float, offset: Float) = floor(t / FIBRE_PERIOD + offset)
        val next = time + frameDt
        drift.use()
        drift.set("uDt", frameDt)
        drift.set(
            "uRestart",
            if (beat(next, 0f) > beat(time, 0f)) 1f else 0f,
            if (beat(next, 0.5f) > beat(time, 0.5f)) 1f else 0f,
        )
        driftTargets[1].bind()
        bindTexture(0, flowTargets[0].texture)
        bindTexture(1, driftTargets[0].texture)
        drawFullscreen()
        driftTargets.swap()
        time = next
    }

    /** Draws the marble from where the oils are now into [into]. */
    fun compose(into: Target) {
        display.use()
        displayTarget.bind()
        bindTexture(0, flowTargets[0].texture)
        drawFullscreen()

        motion.use()
        motion.set("uTime", time)
        bindTexture(0, displayTarget.texture)
        bindTexture(1, sourceTarget.texture)
        bindTexture(2, lowTargets[0].texture)
        bindTexture(3, driftTargets[0].texture)
        into.bind()
        drawFullscreen()
    }

    /** The oils' mean phase and boundary into a 1×1 [into], by way of the blocks. */
    private fun measureInto(into: Target) {
        gather.use()
        gatheredTarget.bind()
        bindTexture(0, flowTargets[0].texture)
        drawFullscreen()
        measure.use()
        into.bind()
        bindTexture(0, gatheredTarget.texture)
        drawFullscreen()
    }

    private fun Array<Target>.swap() {
        val first = this[0]
        this[0] = this[1]
        this[1] = first
    }

    companion object {
        // Coarse on purpose: Cahn–Hilliard's step shrinks with the fourth power
        // of the boundary width in cells, and bilinear sampling keeps the
        // boundaries soft on screen.
        const val FLOW_CELL = 8
        const val MAX_FLOW_STEPS = 16
        // The boundary between the oils, as Cahn–Hilliard sees it: one cell.
        // Its time step shrinks with the fourth power of this width; the
        // Boundary setting's softness is added when the flow is drawn.
        const val FLOW_WIDTH = 1f
        // The flow changes slowly and the pressure persists between frames, so
        // a few iterations a frame keep up.
        const val PRESSURE_ITERATIONS = 10
        const val MARBLE_LOW_DP = 12f
        // marble_motion.frag's PERIOD, s.
        const val FIBRE_PERIOD = 4f
        // A shake: the pull's part a SHAKE_SMOOTH (s) average misses. The
        // churn rises toward it over CHURN_RISE (s) and stirs nothing below
        // SHAKE_FLOOR (m/s²): a hand's tremor or a step. Its eddies shift by
        // CHURN_SHIFT turns as they turn over once.
        const val SHAKE_SMOOTH = 0.2f
        const val CHURN_RISE = 0.15f
        const val SHAKE_FLOOR = 1.5f
        const val CHURN_SHIFT = 0.3f
        // flow_gather.frag's GATHER: cells per block.
        const val GATHER = 4
        // How fast the white oil's lost share comes back, 1/s.
        const val RESTORE_RATE = 0.5f
        // How much farther than the attraction the crowding reaches.
        const val CROWDING_REACH = 2f
    }
}
