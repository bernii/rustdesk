package com.carriez.flutter_hbb.xr.glass

import android.opengl.GLES30.GL_RGBA32F
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * touch-glass-exp's EdgeMagnet for the trackpad, whose oil sits off the
 * screen's center and changes shape: [setShape] takes the oil's center, and
 * [reshape] also moves the oil when its rest outline moves.
 *
 * The edge magnet: an edge near a finger sticks to it. The same as the web
 * playground's updateMagnets() and reshapeOil(); its README has the details.
 *
 * The touch is its contact ellipse, at TOUCH_EDGE of its radius, where the
 * meniscus collar rises around it. When that outline comes within Magnet
 * range of the edge, inside the oil or out, the edge is caught at the
 * outline's point nearest it, or where the touch already overlaps the edge,
 * at the edge itself. That point sticks to the finger, and the edge follows
 * it, Magnet out of the way out past the oil, or Magnet in of the way in; the
 * pull covers the whole touch and fades smoothly beyond it (oil.glsl), so a
 * finger laid along the border pulls that whole stretch of it. Once the point
 * is Magnet reach from where the edge rests, the edge lets go and springs
 * back, the touch's shape staying where it was. The pull rides a spring, so
 * the edge jumps to the touch when caught and overshoots when let go. Only the
 * screen's own edge stops it: a wall with the display's rounded corners
 * ([screenCorner]), which the oil meets a little softly, so it doesn't end in
 * a sharp corner there.
 *
 * While any magnet is pulling or springing back, [reshape] rebuilds the rest data
 * and moves the oil with its edge (sim_reshape.frag), with two blurs. They're
 * wide, so they run at half resolution (sim_halve.frag): at full, they took
 * the frame rate from 120 down to 90.
 *
 * [rest] is the sim_rest program with the oil's shape set. [build] compiles
 * a program from the named shader assets; [blur] runs a separable blur from a
 * texture, through a scratch target, into a target of the same size, σ in its
 * texels.
 */
class EdgeMagnet(
    private val touch: TouchParams,
    private val density: Float,
    private val rest: Program,
    build: (name: String, chunks: List<String>) -> Program,
    private val blur: (source: Int, scratch: Target, into: Target, sigma: Float) -> Unit,
) {
    private class Magnet(val touchId: Int, val dx: Float, val dy: Float) {
        var held = true
        // The touch: its center, major axis and radii along and across it, px.
        var x = 0f
        var y = 0f
        var ux = 0f
        var uy = 1f
        var a = 1f
        var b = 1f
        // How far the edge is pulled, px, on a spring.
        var pull = 0f
        var speed = 0f
        var target = 0f
    }

    private val reshapeProgram = build("sim_reshape", listOf("common.glsl", "sim.glsl", "sim_reshape.frag"))
    private val level = build("pad_level", listOf("common.glsl", "sim.glsl", "pad_level.frag"))
    private val halve = build("sim_halve", listOf("common.glsl", "sim_halve.frag"))
    private val magnets = ArrayList<Magnet>()

    private var width = 0
    private var height = 0
    private var halfW = 0f
    private var halfH = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var smooth = 2f

    private lateinit var spare: Target
    private lateinit var delta: Target
    private lateinit var share: Target
    // At half resolution.
    private lateinit var halved: Target
    private lateinit var scratch: Target
    private lateinit var around: Target
    private lateinit var spread: Target
    private var sigma = 1f

    // The magnets as oil.glsl reads them.
    private val data = FloatArray(MAX_MAGNETS * 4)
    private val axes = FloatArray(MAX_MAGNETS * 4)
    private var restPulled = false

    /** The display's corner radius, px; 0 for SCREEN_CORNER of its width. */
    var screenCorner = 0f

    /** Pulling the edge, springing back, or not yet back at rest. */
    val active get() = magnets.isNotEmpty() || restPulled

    /** Allocates the reshape's targets on the simulation grid; [target] registers each. */
    fun resize(w: Int, h: Int, simW: Int, simH: Int, target: (Int, Int, Int) -> Target) {
        width = w
        height = h
        spare = target(simW, simH, GL_RGBA32F)
        delta = target(simW, simH, GL_RGBA32F)
        share = target(simW, simH, GL_RGBA32F)
        val blocksW = (simW + 1) / 2
        val blocksH = (simH + 1) / 2
        halved = target(blocksW, blocksH, GL_RGBA32F)
        scratch = target(blocksW, blocksH, GL_RGBA32F)
        around = target(blocksW, blocksH, GL_RGBA32F)
        spread = target(blocksW, blocksH, GL_RGBA32F)
        magnets.clear()
        restPulled = false
        // The volume a move adds or takes is spread over about half the bump;
        // σ in half-resolution texels.
        sigma = max(touch.magnetWidth / 2f, 6f) * density / CELL / 2f

        reshapeProgram.use()
        reshapeProgram.setInt("uState", 0)
        reshapeProgram.setInt("uRest", 1)
        reshapeProgram.setInt("uRestOld", 2)
        reshapeProgram.setInt("uDelta", 3)
        reshapeProgram.setInt("uAround", 4)
        reshapeProgram.setInt("uSpread", 5)
        reshapeProgram.setInt("uSpreadScale", 2)
        halve.use()
        halve.setInt("uSrc", 0)
        level.use()
        level.setInt("uState", 0)
        level.setInt("uRest", 1)
        level.setInt("uRestOld", 2)
        reshapeProgram.set("uWake", touch.magnetWake)
        reshapeProgram.set("uWakeBand", WAKE_BAND_DP * density)
    }

    /** The oil's outline at rest, as pad_oil.glsl's uniforms give it, px; its center from the screen's. */
    fun setShape(halfWidth: Float, halfHeight: Float, x: Float, y: Float, cornerRadius: Float, exponent: Float) {
        halfW = halfWidth
        halfH = halfHeight
        centerX = x
        centerY = y
        radius = cornerRadius
        smooth = exponent
    }

    /** Catches, follows and lets go of the edge for this frame's fingers. */
    fun update(dt: Float, frame: FrameFingers) {
        for (m in magnets) {
            if (m.held && (0 until frame.count).none { frame.touch[it] == m.touchId }) m.held = false
        }
        for (k in 0 until frame.count) {
            // The touch's outline: its contact ellipse, or a circle without one.
            val hasContact = frame.major[k] > 0f
            val ux = if (hasContact) sin(frame.angle[k]) else 0f
            val uy = if (hasContact) -cos(frame.angle[k]) else 1f
            val a = TOUCH_EDGE * if (hasContact) 0.5f * frame.major[k] * touch.contactScale else touch.radius * density
            val b = TOUCH_EDGE * if (hasContact) 0.5f * frame.minor[k] * touch.contactScale else touch.radius * density
            var m = magnets.firstOrNull { it.held && it.touchId == frame.touch[k] }
            if (m == null) {
                val cx = frame.toX[k] - width / 2f
                val cy = frame.toY[k] - height / 2f
                val d = restSdf(cx, cy)
                // The outward normal, and how far the outline reaches toward the edge.
                var nx = restSdf(cx + 1f, cy) - restSdf(cx - 1f, cy)
                var ny = restSdf(cx, cy + 1f) - restSdf(cx, cy - 1f)
                val n = hypot(nx, ny).takeIf { it > 0f } ?: 1f
                nx /= n
                ny /= n
                val r = hypot(a * (nx * ux + ny * uy), b * (ny * ux - nx * uy))
                // From the edge out to the outline, px: 0 while the touch overlaps it.
                val gap = if (d > r) d - r else if (d < -r) d + r else 0f
                if (touch.magnetOut <= 0f && touch.magnetIn <= 0f || abs(gap) >= touch.magnetRange * density || magnets.size >= MAX_MAGNETS) continue
                // Caught at the outline facing the edge, or on the edge if it's closer.
                val toward = -d.coerceIn(-r, r)
                m = Magnet(frame.touch[k], nx * toward, ny * toward).also {
                    it.x = frame.toX[k]
                    it.y = frame.toY[k]
                    magnets += it
                }
            }
            val x = frame.toX[k] + m.dx
            val y = frame.toY[k] + m.dy
            val held = restSdf(x - width / 2f, y - height / 2f)
            if (abs(held) > touch.magnetReach * density) {
                m.held = false
                continue
            }
            // The pull centers on the touch, whose outline passes through the point.
            m.x = x - m.dx
            m.y = y - m.dy
            m.ux = ux
            m.uy = uy
            m.a = a
            m.b = b
            // Out past the oil and in pull with their own strengths.
            m.target = (if (held > 0f) touch.magnetOut else touch.magnetIn) * held
        }
        val k = 2f * PI.toFloat() * MAGNET_HZ
        for (m in magnets) {
            if (!m.held) m.target = 0f
            m.speed += (k * k * (m.target - m.pull) - 2f * MAGNET_DAMPING * k * m.speed) * dt
            m.pull += m.speed * dt
        }
        magnets.removeAll { !it.held && abs(it.pull) < 0.05f && abs(it.speed) < 1f }
    }

    /** Sets the magnets on [program], for oil.glsl. */
    fun setOn(program: Program) {
        val fade = touch.magnetWidth * density
        magnets.forEachIndexed { i, m ->
            data[i * 4] = m.x - width / 2f
            data[i * 4 + 1] = m.y - height / 2f
            data[i * 4 + 2] = m.pull
            data[i * 4 + 3] = fade
            axes[i * 4] = m.ux
            axes[i * 4 + 1] = m.uy
            axes[i * 4 + 2] = m.a
            axes[i * 4 + 3] = m.b
        }
        program.setInt("uMagnetCount", magnets.size)
        program.setVec4s("uMagnets", data)
        program.setVec4s("uMagnetAxes", axes)
        program.set(
            "uScreenWall",
            width / 2f,
            height / 2f,
            screenCorner.takeIf { it > 0f } ?: (SCREEN_CORNER * width),
            WALL_SOFTNESS_DP * density,
        )
    }

    /**
     * Moves the oil with its edge: rebuilds the rest data with the magnets and
     * reshapes [state] (swapping it) from the rest data in [restTarget], when
     * a magnet acts or the rest outline [moved]. Returns the rest target to
     * use from now on.
     *
     * When the rest outline moved, the pad itself moved, by far more than a
     * finger pulls: the oil keeps its depth above rest where it stays and
     * starts at rest where it's new (pad_level.frag), and OilVolume spreads
     * the difference over the puddle. Otherwise the magnets' oil is moved
     * locally, as touch-glass-exp does.
     */
    fun reshape(state: Array<Target>, restTarget: Target, zero: Target, moved: Boolean): Target {
        if (!active && !moved) return restTarget
        rest.use()
        setOn(rest)
        spare.bind()
        drawFullscreen()

        if (moved) {
            level.use()
            bindTexture(0, state[0].texture)
            bindTexture(1, spare.texture)
            bindTexture(2, restTarget.texture)
            state[1].bind()
            drawFullscreen()
            val first = state[0]
            state[0] = state[1]
            state[1] = first
            val reshaped = spare
            spare = restTarget
            restPulled = magnets.isNotEmpty()
            return reshaped
        }

        fun pass(index: Int, into: Target) {
            reshapeProgram.use()
            reshapeProgram.setInt("uPass", index)
            bindTexture(0, state[0].texture)
            bindTexture(1, spare.texture)
            bindTexture(2, restTarget.texture)
            // The first pass writes what the others read.
            bindTexture(3, if (index == 0) zero.texture else delta.texture)
            bindTexture(4, if (index == 0) zero.texture else around.texture)
            bindTexture(5, if (index == 0) zero.texture else spread.texture)
            into.bind()
            drawFullscreen()
        }
        fun halveIt(source: Target): Int {
            halve.use()
            bindTexture(0, source.texture)
            halved.bind()
            drawFullscreen()
            return halved.texture
        }
        pass(0, delta)
        blur(halveIt(delta), scratch, around, sigma)
        pass(1, share)
        blur(halveIt(share), scratch, spread, sigma)
        pass(2, state[1])
        val first = state[0]
        state[0] = state[1]
        state[1] = first

        val reshaped = spare
        spare = restTarget
        restPulled = magnets.isNotEmpty()
        return reshaped
    }

    // pad_oil.glsl's restSdf: signed distance in px from (x, y), px from the
    // screen center (negative inside).
    private fun restSdf(x: Float, y: Float): Float {
        val qx = abs(x - centerX) - halfW + radius
        val qy = abs(y - centerY) - halfH + radius
        if (qx > 0f && qy > 0f) {
            val len = (qx.pow(smooth) + qy.pow(smooth)).pow(1f / smooth)
            val gx = (qx / max(len, 1e-4f)).pow(smooth - 1f)
            val gy = (qy / max(len, 1e-4f)).pow(smooth - 1f)
            return (len - radius) / hypot(gx, gy)
        }
        return max(qx, qy) - radius
    }

    private companion object {
        const val CELL = GlassRenderer.CELL
        const val MAX_MAGNETS = 4 // oil.glsl's
        const val MAGNET_HZ = 4f
        const val MAGNET_DAMPING = 0.3f // of critical
        const val TOUCH_EDGE = 0.75f
        // The screen's wall: its corner radius as a share of its width, where
        // the display doesn't say, and how soft the oil meets it.
        const val SCREEN_CORNER = 0.115f
        const val WALL_SOFTNESS_DP = 2.5f
        // How far in from the edge its wake reaches, about.
        const val WAKE_BAND_DP = 24f
    }
}
