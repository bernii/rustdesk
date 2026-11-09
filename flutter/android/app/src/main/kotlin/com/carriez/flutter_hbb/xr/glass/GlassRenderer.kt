package com.carriez.flutter_hbb.xr.glass

import android.content.res.AssetManager
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Trace
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The trackpad, every pixel of it: touch-glass-exp's renderer with its oil
 * shaped and placed as the pad, under the design's controls.
 *
 * When the surface is created or resized: the background (or the moving
 * marble's start), its frost, the oil's rest state, the dots and the legend.
 *
 * Every frame: the scene's springs; the oil's rest outline wherever the pad
 * now is, moving the oil with it (the edge magnet's reshape) and baking the
 * dots again; the edge magnet; shallow-water substeps, the surface and dot
 * drift; with the moving marble, the oils, the marble drawn from them and its
 * frost; then the pad's composite (pad_glass.frag), where calm quads compose
 * the oil at rest without the simulation; then the controls (ui.frag) over
 * it, in the same pass.
 *
 * Uniforms that only depend on the parameters are set once; per frame only
 * the time step, the fingers, the pad's outline, the legend, the device's
 * motion and the light change.
 */
class GlassRenderer(
    private val assets: AssetManager,
    private val params: TouchGlassParams,
    private val density: Float,
    private val fingers: Fingers,
    private val deviceMotion: DeviceMotion,
    private val scene: Scene,
    private val listener: Listener,
) : GLSurfaceView.Renderer {

    /** The display's corner radius, px, for the edge magnet's wall; 0 if unknown. Set on the GL thread. */
    var screenCorner = 0f
        set(value) {
            field = value
            magnet?.screenCorner = value
        }

    /** The marble moves, so the screen changes every frame even when untouched. */
    val moving = params.background.style == "marble" && params.background.motion

    /** The light stays put in the room as the phone turns, so it moves on the screen every frame. */
    private val liveLight = params.liquid.tiltLight

    /** Something on screen changes every frame, touched or not. */
    val animated = moving || liveLight

    /**
     * Debuggable builds only: a file whose text, "nosim" or "flat", cuts the glass's effects to
     * measure their cost to the glasses' view. Read about once a second; absent means full.
     */
    var effectsFile: java.io.File? = null
    private var effects = EFFECTS_FULL
    private var effectsRead = 0L

    interface Listener {
        /** About once a second, on the GL thread. */
        fun onStats(text: String)

        /** Nothing has moved for a while and no finger is down; the frame rate can drop. */
        fun onSettled()

        /** Where the glass the views follow is this frame, on the GL thread; only when it changed. */
        fun onFrame(frame: Scene.Frame)
    }

    private lateinit var background: Program
    private lateinit var blur: Program
    private lateinit var glass: Program
    private var marble: MarbleFlow? = null
    private var magnet: EdgeMagnet? = null
    private val gforce = GForce(params.gforce, density)
    private lateinit var rest: Program
    private lateinit var dotCells: Program
    private lateinit var step: Program
    private lateinit var surface: Program
    private lateinit var drift: Program
    private lateinit var ui: UiPass
    private lateinit var volume: OilVolume

    private val targets = ArrayList<Target>()
    private lateinit var backgroundTarget: Target
    private lateinit var frostTargets: Array<Target>
    private lateinit var restTarget: Target
    private lateinit var stateTargets: Array<Target>
    private lateinit var surfaceTarget: Target
    private lateinit var driftTargets: Array<Target>
    private lateinit var dotTarget: Target
    private lateinit var zeroTarget: Target
    private var legend = 0

    private var width = 0
    private var heightPx = 0

    // Simulation constants for the current surface, in grid cells.
    // The wave speed, cells/s, and the explicit-step limits that don't depend
    // on the oil's weight (capillary, × the Laplacian's largest eigenvalue;
    // viscous).
    private var waveSpeed = 0f
    private var capillaryTerm = 0f
    private var viscousDt = 0f
    private var radiusCells = 0f
    private val fingerData = FloatArray(MAX_FINGERS * 4)
    private val shapeData = FloatArray(MAX_FINGERS * 4)
    private val frame = FrameFingers()

    // The pad's outline the oil was last shaped to, px.
    private var shaped: Box? = null
    private var corner = SquircleCorner(params.squircle)
    private var lastPublished: Scene.Frame? = null
    private var lightX = 0f
    private var lightY = 0f

    private var lastFrame = 0L
    private var lastReshape = 0L
    // When the G-force last moved the oil.
    private var lastMotion = 0L
    // The simulation's clock, s, for the refill's unevenness; wrapped so it
    // stays precise.
    private var simClock = 0f
    private var volumeRead = 0L
    private var keepIn = 0
    private var lastBusy = 0L
    private var lastFrameStart = 0L
    private var lastEnd = 0L
    private var lastInterval = 0L
    private var settled = false
    // The marble's time not yet stepped, s, and whether this frame draws it.
    private var marbleDt = 0f
    private var marbleDrawNext = false
    private val timer = GpuTimer()
    private val stats = Stats()

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        val version = glGetString(GL_VERSION) ?: ""
        val extensions = glGetString(GL_EXTENSIONS) ?: ""
        check("EXT_color_buffer_float" in extensions || version.startsWith("OpenGL ES 3.2")) {
            "Float render targets (EXT_color_buffer_float) aren't supported: $version"
        }
        val vertex = read("fullscreen.vert")
        // touch-glass-exp's chunks, with this app's oil (pad_oil.glsl) in
        // place of oil.glsl.
        fun build(name: String, chunks: List<String>) =
            Program(vertex, HEADER + chunks.joinToString("\n") { read(if (it == "oil.glsl") "pad_oil.glsl" else it) }, name)
        background = build("background", listOf("common.glsl", "marble.glsl", "background.frag"))
        blur = build("blur", listOf("common.glsl", "blur.frag"))
        glass = build("pad_glass", listOf("common.glsl", "oil.glsl", "pad_glass.frag"))
        rest = build("sim_rest", listOf("common.glsl", "oil.glsl", "sim_rest.frag"))
        dotCells = build("dot_cells", listOf("common.glsl", "oil.glsl", "dot_cells.frag"))
        step = build("sim_step", listOf("common.glsl", "sim.glsl", "sim_step.frag"))
        surface = build("sim_surface", listOf("common.glsl", "sim.glsl", "sim_surface.frag"))
        drift = build("sim_drift", listOf("common.glsl", "sim.glsl", "sim_drift.frag"))
        marble = if (moving) MarbleFlow(params.background, density, ::build, ::blurInto) else null
        // Always built: besides the magnets, its reshape moves the oil when
        // the pad changes shape.
        magnet = EdgeMagnet(params.touch, density, rest, ::build, ::blurInto).also { it.screenCorner = screenCorner }
        ui = UiPass(::build, density, SquircleCorner(params.squircle))
        volume = OilVolume(::build)

        // Attribute-less drawing still needs a vertex array bound.
        val vao = IntArray(1)
        glGenVertexArrays(1, vao, 0)
        glBindVertexArray(vao[0])
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        timer.init("GL_EXT_disjoint_timer_query" in extensions)
        targets.clear() // the old context, and its objects, are gone
        legend = 0
        Log.i(TAG, "GL: $version, ${glGetString(GL_RENDERER)}, GPU timer ${timer.available}")
    }

    override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
        width = w
        heightPx = h
        targets.forEach { it.release() }
        targets.clear()
        fun target(tw: Int, th: Int, format: Int = GL_RGBA8) = Target(tw, th, format).also { targets += it }

        val simW = ceil(w / CELL.toFloat()).toInt()
        val simH = ceil(h / CELL.toFloat()).toInt()
        val spacing = params.dots.spacing * density
        val dotCols = ceil(w / spacing).toInt() + 4
        val dotRows = ceil(h / (spacing * 0.8660254f)).toInt() + 4
        backgroundTarget = target(w, h)
        // Frost at quarter size: the blur is wide, and the moving marble
        // redoes it every time it's drawn.
        frostTargets = arrayOf(target(w / 4, h / 4), target(w / 4, h / 4))
        restTarget = target(simW, simH, GL_RGBA32F)
        stateTargets = arrayOf(target(simW, simH, GL_RGBA32F), target(simW, simH, GL_RGBA32F))
        surfaceTarget = target(simW, simH, GL_RGBA16F)
        driftTargets = arrayOf(target(simW, simH, GL_RGBA16F), target(simW, simH, GL_RGBA16F))
        dotTarget = target(dotCols, dotRows)
        zeroTarget = target(1, 1)
        marble?.resize(w, h) { tw, th, format -> target(tw, th, format) }
        magnet?.resize(w, h, simW, simH) { tw, th, format -> target(tw, th, format) }
        volume.resize(simW, simH) { tw, th, format -> target(tw, th, format) }
        targets.forEach { it.clear() }
        makeLegend()
        ui.resize(w, h)

        setStaticUniforms(dotCols / 2, dotRows / 2)
        renderStatic()
        lastFrame = 0L
        Log.i(TAG, "Surface ${w}×$h, simulation ${simW}×$simH, dots ${dotCols}×$dotRows, density $density")
    }

    override fun onDrawFrame(unused: GL10?) {
        val start = System.nanoTime()
        // Long gaps (paused, settled) shouldn't turn into one huge step.
        val frameDt = if (lastFrame == 0L) 1f / 60f else min((start - lastFrame) / 1e9f, 1f / 30f)
        lastFrame = start
        effectsFile?.let { file ->
            if (start - effectsRead > 1_000_000_000L) {
                effectsRead = start
                effects = when (runCatching { file.readText().trim() }.getOrNull()) {
                    "nosim" -> EFFECTS_NO_SIM
                    "flat" -> EFFECTS_FLAT
                    else -> EFFECTS_FULL
                }
            }
        }
        Trace.beginSection("scene")
        scene.advance(start, frameDt)
        Trace.endSection()

        timer.begin(0)
        Trace.beginSection("shapeOil")
        val reshaped = shapeOil()
        Trace.endSection()
        val simDt = frameDt * params.fluid.timeScale
        fingers.takeFrame(start, simDt, density, frame)
        if (reshaped) lastReshape = start
        if (scene.busy) lastBusy = start
        gforce.update(frameDt, deviceMotion)
        if (gforce.active) lastMotion = start
        // The oil has settled 6 s after the last touch, move of the pad or
        // G-force on it, and stays put until the next, so it isn't stepped
        // meanwhile.
        val oilQuiet = frame.count == 0 && magnet?.active != true &&
            start - maxOf(fingers.lastActivity, lastReshape, lastMotion) > SETTLE_NANOS
        Trace.beginSection("simulate")
        val steps = if (oilQuiet || effects != EFFECTS_FULL) 0 else stepSimulation(simDt, reshaped)
        Trace.endSection()
        timer.end()
        marble?.takeIf { effects == EFFECTS_FULL }?.let {
            // Its oils step on one frame and it's drawn and frosted on the next,
            // so each frame does half its work. With the glasses attached the
            // phone's screen is held at 60 Hz, and the marble moves slowly
            // enough that 30 is plenty. It doesn't follow the frame time: a late
            // frame mustn't make the next do both halves, and come late too.
            marbleDt += frameDt
            if (!marbleDrawNext) {
                timer.begin(1)
                Trace.beginSection("marble.step")
                it.step(marbleDt, deviceMotion.forceX, deviceMotion.forceY)
                Trace.endSection()
                timer.end()
                marbleDt = 0f
            }
            if (marbleDrawNext) {
                timer.begin(2)
                Trace.beginSection("marble.compose")
                it.compose(backgroundTarget)
                frost()
                Trace.endSection()
                timer.end()
            }
            marbleDrawNext = !marbleDrawNext
        }
        val simulated = System.nanoTime()
        // One timer for both: on a tiled GPU they're one render pass, which a
        // query between them would only split.
        timer.begin(3)
        Trace.beginSection("composite")
        if (effects == EFFECTS_FLAT) {
            bindScreen(width, heightPx)
            glClearColor(0.08f, 0.08f, 0.09f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
        } else {
            composite()
        }
        Trace.endSection()
        Trace.beginSection("controls")
        ui.draw(scene, frostTargets[1].texture, lightX, lightY)
        Trace.endSection()
        timer.end()
        timer.nextFrame()
        val end = System.nanoTime()
        // At 120 Hz, a frame that took longer than one and a half.
        val interval = start - lastFrameStart
        if (DEBUG_SLOW && lastEnd != 0L && interval > 12_500_000L && lastInterval < 11_000_000L) {
            Log.w(TAG, "slow: %.1f ms since last, swap wait %.1f, sim %.1f, marble+glass %.1f, reshaped %b".format(
                (start - lastFrameStart) / 1e6, (start - lastEnd) / 1e6, (simulated - start) / 1e6, (end - simulated) / 1e6, reshaped))
        }
        lastEnd = end
        lastFrameStart = start
        lastInterval = interval

        // The composite's CPU time includes any wait for a free display buffer.
        stats.add(start, simulated - start, System.nanoTime() - simulated, steps, frame, marble)
        if (gforce.active) {
            val (light, heavy) = gforce.weightRange()
            stats.motion = " · g-force %.1f m/s² weight %d–%d%%".format(gforce.felt, light, heavy)
        }
        if (start - volumeRead > 1_000_000_000L) {
            volumeRead = start
            volume.read()
            stats.oil = volume.share
        }
        stats.report(start, timer)?.let {
            val tag = when (effects) {
                EFFECTS_NO_SIM -> " · effects nosim"
                EFFECTS_FLAT -> " · effects flat"
                else -> if (effectsFile != null) " · effects full" else ""
            }
            Log.i(TAG, it.replace('\n', ' ') + tag)
            listener.onStats(it)
        }
        publish()

        // Settled once the oil is and nothing else has moved for a moment:
        // dropping the rate between two animations would only switch the
        // display's mode back and forth, which drops a frame each time.
        val quiet = oilQuiet && start - lastBusy > SCENE_SETTLE_NANOS
        if (!quiet) settled = false
        if (quiet && !settled) {
            settled = true
            listener.onSettled()
        }
    }

    private fun publish() {
        if (!scene.ready) return
        val next = scene.frame()
        val last = lastPublished
        val eps = 0.25f
        if (last != null && last.pad.same(next.pad, eps) && last.field.same(next.field, eps) &&
            abs(last.fieldFade - next.fieldFade) < 1e-3f && abs(last.panelGrow - next.panelGrow) < 1e-3f &&
            last.settings.same(next.settings, eps) && abs(last.settingsFade - next.settingsFade) < 1e-3f &&
            abs(last.settingsPanelGrow - next.settingsPanelGrow) < 1e-3f
        ) {
            return
        }
        lastPublished = next
        listener.onFrame(next)
    }

    /**
     * The oil's rest outline where the pad is now. When the pad has moved,
     * sets the outline on every program that draws the oil and bakes the dots
     * again, and returns true: the simulation then moves the oil with it. Its
     * first outline is baked at rest instead.
     */
    private fun shapeOil(): Boolean {
        if (!scene.ready) return false
        val pad = scene.shape(Part.PAD)
        val last = shaped
        if (last != null && last.same(pad, 0.01f)) return false
        shaped = pad
        val halfW = max(pad.w / 2f, 1f)
        val halfH = max(pad.h / 2f, 1f)
        val cx = pad.cx - width / 2f
        val cy = pad.cy - heightPx / 2f
        val radius = min(corner.extentDp * density, min(halfW, halfH))
        for (program in listOf(rest, dotCells, glass)) {
            program.use()
            program.set("uHalfSize", halfW, halfH)
            program.set("uOilCenter", cx, cy)
            program.set("uRadius", radius)
        }
        magnet?.setShape(halfW, halfH, cx, cy, radius, corner.exponent)
        gforce.setShape(width, heightPx, halfW, halfH, params.liquid.thickness * density)
        dotTarget.bind()
        dotCells.use()
        drawFullscreen()
        if (last == null) {
            bakeRest()
            return false
        }
        return true
    }

    /** The oil at rest, with no magnet pulling its edge: its rest data, and no waves or drift. */
    private fun bakeRest() {
        restTarget.bind()
        rest.use()
        rest.setInt("uMagnetCount", 0)
        drawFullscreen()
        for (t in stateTargets + driftTargets) t.clear()
        volume.rebase(stateTargets[0], restTarget, zeroTarget)
    }

    /** Steps the oil [simDt] s with this frame's fingers, moving it first if the pad [reshaped]. Returns the substeps. */
    private fun stepSimulation(simDt: Float, reshaped: Boolean): Int {
        val fluid = params.fluid
        val touch = params.touch
        // The fastest wave is gravity's, as heavy as the G-force makes the
        // oil, plus capillary, in the thickest oil allowed.
        val omega = waveSpeed * sqrt(MAX_THICKNESS * 8f * (gforce.heaviest + capillaryTerm))
        val dtMax = min(1.6f / omega, viscousDt)
        val steps = min(max(1, ceil(simDt / dtMax).toInt()), MAX_SUBSTEPS)
        val dt = min(simDt / steps, dtMax)

        magnet?.let {
            it.update(simDt, frame)
            Trace.beginSection("reshape")
            restTarget = it.reshape(stateTargets, restTarget, zeroTarget, reshaped)
            Trace.endSection()
        }
        val n = frame.count
        for (k in 0 until n) {
            // Each finger is its contact ellipse, scaled so the dry spot it opens
            // roughly matches the contact; without a contact, a circle.
            val hasContact = frame.major[k] > 0f
            val a = if (hasContact) 0.5f * frame.major[k] * touch.contactScale / CELL else radiusCells
            val b = if (hasContact) 0.5f * frame.minor[k] * touch.contactScale / CELL else radiusCells
            // Clockwise from vertical on screen is (sin, cos) on the grid, y up.
            shapeData[k * 4] = sin(frame.angle[k])
            shapeData[k * 4 + 1] = cos(frame.angle[k])
            shapeData[k * 4 + 2] = max(a, 1f)
            shapeData[k * 4 + 3] = max(b, 1f)
        }

        step.use()
        step.set("uDt", dt)
        simClock = (simClock + simDt) % 1000f
        step.set("uTime", simClock)
        gforce.setOnStep(step, waveSpeed)
        step.setInt("uFingerCount", n)
        step.setVec4s("uFingerShapes", shapeData)
        bindTexture(1, restTarget.texture)

        for (i in 0 until steps) {
            val t = (i + 1f) / steps
            for (k in 0 until n) {
                val x = frame.fromX[k] + (frame.toX[k] - frame.fromX[k]) * t
                val y = frame.fromY[k] + (frame.toY[k] - frame.fromY[k]) * t
                var push = touch.push * (1f + touch.speedPush * min(frame.speed[k] / fluid.waveSpeed, 3f))
                if (frame.kick[k] && i == 0) push += touch.tapImpulse * KICK_S / dt
                fingerData[k * 4] = x / CELL
                fingerData[k * 4 + 1] = (heightPx - y) / CELL
                fingerData[k * 4 + 2] = push
            }
            step.setVec4s("uFingers", fingerData)
            pingPong(stateTargets)
        }

        // Whatever the pad's moves or the magnets' added or took goes back
        // the same frame. Fast drags lose a few hundredths of a percent to the
        // thickness limit, so every few frames is enough otherwise.
        keepIn--
        if (reshaped || magnet?.active == true || keepIn <= 0) {
            volume.keep(stateTargets, restTarget)
            keepIn = KEEP_EVERY
        }

        drift.use()
        drift.set("uDt", simDt)
        bindTexture(2, driftTargets[0].texture)
        driftTargets[1].bind()
        bindTexture(0, stateTargets[0].texture)
        drawFullscreen()
        driftTargets.swap()

        surface.use()
        surfaceTarget.bind()
        bindTexture(0, stateTargets[0].texture)
        drawFullscreen()
        return steps
    }

    private fun pingPong(pair: Array<Target>) {
        pair[1].bind()
        bindTexture(0, pair[0].texture)
        drawFullscreen()
        pair.swap()
    }

    private fun Array<Target>.swap() {
        val first = this[0]
        this[0] = this[1]
        this[1] = first
    }

    private fun composite() {
        bindScreen(width, heightPx)
        glass.use()
        magnet?.setOn(glass)
        aimLight()
        // The legend rides the pad's center, scrolled and zoomed by two
        // fingers; the dots dim around it.
        if (scene.ready) {
            val pad = scene.shape(Part.PAD)
            val x = pad.cx - width / 2f + scene.legendX
            val y = pad.cy - heightPx / 2f + scene.legendY
            val zoom = scene.legendZoom
            val unit = scene.geometry!!.unit
            glass.set("uLegendBox", x, y, 0.5f * Legend.WIDTH_DP * density * zoom, 0.5f * Legend.HEIGHT_DP * density * zoom)
            glass.set("uLegendCalm", x, y, CALM_RX * unit * zoom, CALM_RY * unit * zoom)
        }
        bindTexture(0, backgroundTarget.texture)
        bindTexture(1, frostTargets[1].texture)
        bindTexture(2, surfaceTarget.texture)
        bindTexture(3, driftTargets[0].texture)
        bindTexture(4, dotTarget.texture)
        bindTexture(5, legend)
        drawFullscreen()
    }

    /**
     * The light. With Tilt moves the light, where the phone's turn since the
     * app started has moved it: it stays put in the room, but its height stays
     * within TILT_LIGHT_RANGE of where it started, by default straight
     * overhead. Sets the glass's light direction and caustic shift.
     */
    private fun aimLight() {
        val liquid = params.liquid
        val a = Math.toRadians(liquid.lightAngle.toDouble()).toFloat()
        val h = Math.toRadians(liquid.lightHeight.toDouble()).toFloat()
        // Toward the light when the app started, device axes (x right, y up,
        // z out of the screen), then where the phone's turn has taken it.
        val sx = cos(h) * cos(a)
        val sy = cos(h) * sin(a)
        val sz = sin(h)
        val m = if (liveLight) deviceMotion.turned else null
        val lx = if (m != null) m[0] * sx + m[1] * sy + m[2] * sz else sx
        val ly = if (m != null) m[3] * sx + m[4] * sy + m[5] * sz else sy
        val lz = if (m != null) m[6] * sx + m[7] * sy + m[8] * sz else sz
        val height = Math.toDegrees(asin(lz.coerceIn(-1f, 1f)).toDouble()).toFloat()
            .coerceIn(liquid.lightHeight - TILT_LIGHT_RANGE, min(liquid.lightHeight + TILT_LIGHT_RANGE, 90f))
        // Its direction; straight overhead it has none, so the set direction
        // eases in there.
        var dx = lx + OVERHEAD_EASE * cos(a)
        var dy = ly + OVERHEAD_EASE * sin(a)
        val length = sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: 1f
        dx /= length
        dy /= length
        val slant = cos(Math.toRadians(height.toDouble())).toFloat() / liquid.ior
        val reach = slant / sqrt(1f - slant * slant)
        lightX = dx
        lightY = -dy
        glass.set("uLightDir", dx, -dy)
        glass.set("uCausticShift", dx * reach, -dy * reach)
        stats.lightAngle = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
        stats.lightHeight = height
    }

    /** Separable Gaussian from [source] through [scratch] into [into] (same size), σ in its texels. */
    private fun blurInto(source: Int, scratch: Target, into: Target, sigma: Float) {
        blur.use()
        blur.setInt("uSrc", 0)
        blur.set("uDstRes", into.width.toFloat(), into.height.toFloat())
        blur.set("uSigma", sigma)
        blur.set("uStep", 1f / into.width, 0f)
        scratch.bind()
        bindTexture(0, source)
        drawFullscreen()
        blur.set("uStep", 0f, 1f / into.height)
        into.bind()
        bindTexture(0, scratch.texture)
        drawFullscreen()
    }

    /** The background frosted: a radius of about 2σ, σ in quarter-size texels. */
    private fun frost() {
        val frostPx = params.liquid.frost * density
        if (frostPx > 0.5f) blurInto(backgroundTarget.texture, frostTargets[0], frostTargets[1], frostPx / 8f)
    }

    private fun makeLegend() {
        if (legend != 0) glDeleteTextures(1, intArrayOf(legend), 0)
        val bitmap = Legend.draw(density)
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        legend = ids[0]
        glBindTexture(GL_TEXTURE_2D, legend)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    private fun setStaticUniforms(dotOriginX: Int, dotOriginY: Int) {
        val (bg, squircle, liquid, dots, fluid, touch) = params
        corner = SquircleCorner(squircle)
        val thickness = liquid.thickness * density
        val slope = tan(Math.toRadians(liquid.contactAngle.toDouble())).toFloat()
        // Until the layout arrives, the oil fills the screen as in touch-glass-exp.
        val pad = squircle.padding * width
        val halfW = width / 2f - pad
        val halfH = heightPx / 2f - pad
        val cornerRadius = min(corner.extentDp * density, min(halfW, halfH))
        fun Program.shape() {
            set("uHalfSize", halfW, halfH)
            set("uOilCenter", 0f, 0f)
            set("uRadius", cornerRadius)
            set("uSmooth", corner.exponent)
            set("uThickness", thickness)
            set("uSlope", slope)
        }
        magnet?.setShape(halfW, halfH, 0f, 0f, cornerRadius, corner.exponent)
        gforce.setShape(width, heightPx, halfW, halfH, thickness)

        background.use()
        background.setMarble(bg, density)
        background.set("uRes", width.toFloat(), heightPx.toFloat())
        background.setInt("uStyle", if (bg.style == "marble") 1 else 0)
        background.set("uScale", bg.scale)
        background.set("uWarp", bg.warp)
        background.set("uContrast", bg.contrast)
        background.set("uLightness", bg.lightness)
        background.set("uGrain", bg.grain)

        rest.use()
        rest.shape()
        rest.set("uRes", width.toFloat(), heightPx.toFloat())
        rest.set("uCell", CELL.toFloat())

        dotCells.use()
        dotCells.shape()
        dotCells.set("uRes", width.toFloat(), heightPx.toFloat())
        dotCells.setInt("uDotOrigin", dotOriginX, dotOriginY)
        dotCells.set("uSpacing", dots.spacing * density)
        dotCells.set("uDotSize", dots.size)
        dotCells.set("uDotLum", dots.luminosity)
        dotCells.set("uDotVariance", dots.variance)
        dotCells.set("uDotGrain", dots.varianceGrain)
        dotCells.set("uDotInset", dots.inset * density)
        dotCells.setInt("uHex", if (dots.hex) 1 else 0)
        dotCells.setUint("uSeed", bg.seed)

        // Past this far in from the edge, the oil at rest is flat to within
        // a slope of 1e-4 and its rim line has faded below half a level.
        val flat = sqrt(1f - 1e-4f / slope)
        val meniscus = 0.5f * ln((1f + flat) / (1f - flat)) * thickness / slope
        val rim = 0.9f * density * ln(max(liquid.rimLine * 510f, 1f))

        val light = Math.toRadians(liquid.lightAngle.toDouble())
        // The light's slant in the oil: its angle from straight down, refracted.
        val slant = cos(Math.toRadians(liquid.lightHeight.toDouble())).toFloat() / liquid.ior
        val reach = slant / sqrt(1f - slant * slant)
        glass.use()
        glass.shape()
        glass.setInt("uBg", 0)
        glass.setInt("uBgFrost", 1)
        glass.setInt("uSurface", 2)
        glass.setInt("uDrift", 3)
        glass.setInt("uDotCells", 4)
        glass.setInt("uLegend", 5)
        glass.setInt("uDotOrigin", dotOriginX, dotOriginY)
        glass.set("uRes", width.toFloat(), heightPx.toFloat())
        glass.set("uCell", CELL.toFloat())
        glass.set("uPlateau", max(meniscus, rim) + 2f)
        glass.set("uFrostOn", if (liquid.frost * density > 0.5f) 1f else 0f)
        glass.set("uFrostFade", liquid.frostFade * density)
        glass.set("uIor", liquid.ior)
        glass.set("uDispersion", liquid.dispersion)
        glass.set("uImageDepth", liquid.imageDepth * density)
        glass.set("uMilkiness", liquid.milkiness)
        glass.set("uVibrance", liquid.vibrance)
        glass.set("uReflections", liquid.reflections)
        glass.set("uRimLine", liquid.rimLine)
        glass.set("uRimWidth", 0.9f * density)
        glass.set("uLightDir", cos(light).toFloat(), -sin(light).toFloat())
        glass.set("uCausticShift", cos(light).toFloat() * reach, -sin(light).toFloat() * reach)
        glass.set("uShadow", liquid.shadow)
        glass.set("uCaustics", fluid.caustics)
        glass.set("uSpacing", dots.spacing * density)
        glass.set("uDotSize", dots.size)
        glass.set("uDotOpacity", dots.opacity)
        glass.set("uDotDepth", dots.depth)
        glass.set(
            "uDotColor",
            ((dots.color shr 16) and 255) / 255f,
            ((dots.color shr 8) and 255) / 255f,
            (dots.color and 255) / 255f,
        )
        glass.setInt("uHex", if (dots.hex) 1 else 0)
        // No legend until the layout arrives: far off screen.
        glass.set("uLegendBox", 1e6f, 1e6f, 1f, 1f)
        glass.set("uLegendCalm", 1e6f, 1e6f, 1f, 1f)

        // Simulation, in grid cells. The explicit-step limits are the fastest
        // wave the grid holds (gravity plus capillary, discrete Laplacian
        // eigenvalue up to 8, in the thickest oil allowed; stepSimulation) and
        // viscous diffusion.
        val toCells = density / CELL
        val c = fluid.waveSpeed * toCells
        val capillary = fluid.surfaceTension * toCells
        val viscosity = fluid.viscosity * toCells * toCells
        waveSpeed = c
        capillaryTerm = capillary * capillary * 8f
        viscousDt = if (viscosity > 0f) 0.2f / viscosity else Float.MAX_VALUE
        radiusCells = touch.radius * toCells

        step.use()
        step.setInt("uState", 0)
        step.setInt("uRest", 1)
        step.set("uWaveSpeed2", c * c)
        step.set("uCapillary2", capillary * capillary)
        step.set("uViscosity", viscosity)
        step.set("uSettling", fluid.settling)
        step.set("uEdgeAbsorption", fluid.edgeAbsorption)
        step.set("uEdgeBand", EDGE_BAND_DP * density)
        step.set("uCell", CELL.toFloat())
        // Where the pad grows, the oil runs back in with less friction, more
        // in some places than others (pad_level.frag marks it, sim_step.frag
        // moves it).
        step.set("uRefillFade", 1f / REFILL_TIME)
        step.set("uRefillEase", REFILL_EASE)
        step.set("uRefillVary", REFILL_VARY)
        step.set("uRefillSize", REFILL_SIZE_DP * toCells)
        step.set("uMeniscus", touch.meniscus)
        step.set("uMaxThickness", MAX_THICKNESS)

        surface.use()
        surface.setInt("uState", 0)
        surface.set("uCell", CELL.toFloat())

        drift.use()
        drift.setInt("uState", 0)
        drift.setInt("uRest", 1)
        drift.setInt("uDrift", 2)
        drift.set("uGain", dots.drift)
        drift.set("uReturnRate", 1f / dots.returnTime)
        drift.set("uMaxDrift", 2f * radiusCells)
    }

    private fun renderStatic() {
        val flow = marble
        if (flow != null) {
            flow.prepare()
            flow.compose(backgroundTarget)
        } else {
            backgroundTarget.bind()
            background.use()
            drawFullscreen()
        }
        frost()

        // The oil at rest where the pad is, or filling the screen until the
        // layout arrives.
        shaped = null
        shapeOil()
        if (shaped == null) {
            bakeRest()
            dotTarget.bind()
            dotCells.use()
            drawFullscreen()
        }
    }

    private fun read(name: String) = assets.open("touchglass/$name").bufferedReader().use { it.readText() }

    /** Frame rate, substeps, CPU and GPU time and the latest contact, once a second. */
    private class Stats {
        private var since = 0L
        private var last = 0L
        private var worst = 0L
        private var frames = 0
        private var simNanos = 0L
        private var compositeNanos = 0L
        private var steps = 0
        private var contact = "no touch"
        private var marble: MarbleFlow? = null

        /** The oil's volume as a share of what it started with. */
        var oil = Float.NaN

        /** The G-force's push and the oil's weight, while it moves the oil; cleared each report. */
        var motion = ""

        /** Where the light is now: its direction and height, degrees. */
        var lightAngle = 0f
        var lightHeight = 90f

        fun add(now: Long, sim: Long, composite: Long, stepCount: Int, frame: FrameFingers, marble: MarbleFlow?) {
            if (since == 0L) since = now
            if (last != 0L) worst = maxOf(worst, now - last)
            last = now
            frames++
            simNanos += sim
            compositeNanos += composite
            steps = stepCount
            this.marble = marble
            // Formatted only when reported: this runs every frame.
            if (frame.count > 0) {
                contactMajor = frame.major[0]
                contactMinor = frame.minor[0]
                contactAngle = frame.angle[0]
                contact = ""
            }
        }

        private var contactMajor = 0f
        private var contactMinor = 0f
        private var contactAngle = 0f

        fun report(now: Long, timer: GpuTimer): String? {
            val elapsed = now - since
            if (elapsed < 1_000_000_000L || frames == 0) return null
            val fps = frames * 1e9 / elapsed
            val cpu = "CPU sim %.2f + glass %.2f ms".format(simNanos / 1e6 / frames, compositeNanos / 1e6 / frames)
            val gpu = timer.averages()?.let { ms ->
                if (ms[1] > 0.0) {
                    "GPU sim %.2f + oils %.2f + marble %.2f + glass %.2f ms".format(ms[0], ms[1], ms[2], ms[3])
                } else {
                    "GPU sim %.2f + glass %.2f ms".format(ms[0], ms[3])
                }
            } ?: "no GPU timer"
            val longest = worst / 1e6
            since = now
            frames = 0
            worst = 0L
            simNanos = 0
            compositeNanos = 0
            val touch = when {
                contact.isNotEmpty() -> contact
                contactMajor > 0f -> "contact %.0f×%.0f px %.0f°".format(contactMajor, contactMinor, Math.toDegrees(contactAngle.toDouble()))
                else -> "touch without contact size"
            }
            val pull = marble?.let { " · pull %.1f m/s² churn %.0f dp/s".format(kotlin.math.hypot(it.pull[0], it.pull[1]), it.churn) } ?: ""
            val light = " · light %.0f° %.0f° high".format(lightAngle, lightHeight)
            val text = "%.0f fps, worst %.1f ms · %d steps · oil %.3f%% · %s\n%s · %s%s%s%s".format(
                fps, longest, steps, oil * 100f, cpu, gpu, touch, pull, motion, light,
            )
            motion = ""
            return text
        }
    }

    /**
     * GPU time of the simulation, the marble's oils, the marble's drawing and
     * the composite with the controls, from EXT_disjoint_timer_query.
     * Reading query results costs about a millisecond of CPU per frame on Mali,
     * so only one frame in SAMPLE_EVERY is timed, and its results are read
     * RING samples later so the queries never stall the GPU.
     */
    private class GpuTimer {
        var available = false
            private set
        private val queries = IntArray(RING * KINDS)
        private val pending = BooleanArray(RING * KINDS)
        private var slot = 0
        private var frameNumber = 0
        private val sums = DoubleArray(KINDS)
        private val counts = IntArray(KINDS)

        fun init(supported: Boolean) {
            available = supported
            pending.fill(false)
            if (supported) glGenQueries(queries.size, queries, 0)
        }

        private val sampling get() = available && frameNumber % SAMPLE_EVERY == 0

        fun begin(kind: Int) {
            if (!sampling) return
            val i = slot * KINDS + kind
            collect(i, kind)
            glBeginQuery(TIME_ELAPSED, queries[i])
            pending[i] = true
        }

        fun end() {
            if (sampling) glEndQuery(TIME_ELAPSED)
        }

        fun nextFrame() {
            if (sampling) slot = (slot + 1) % RING
            frameNumber++
        }

        /**
         * Mean ms per frame for each kind since the last call, counting the
         * frames a kind didn't run as 0: the marble's oils and its drawing
         * take turns at 120 Hz.
         */
        fun averages(): DoubleArray? {
            if (!available || counts[0] == 0 || counts[3] == 0) return null
            val frames = counts[3]
            val result = DoubleArray(KINDS) { sums[it] / frames / 1e6 }
            sums.fill(0.0)
            counts.fill(0)
            return result
        }

        private fun collect(i: Int, kind: Int) {
            if (!pending[i]) return
            val value = IntArray(1)
            glGetQueryObjectuiv(queries[i], GL_QUERY_RESULT_AVAILABLE, value, 0)
            if (value[0] == 0) return
            glGetQueryObjectuiv(queries[i], GL_QUERY_RESULT, value, 0)
            pending[i] = false
            // GL_GPU_DISJOINT_EXT isn't checked: it reports any GPU clock change
            // since the last check, which with sparse samples is nearly always
            // set, not just changes during the timed frame.
            sums[kind] += (value[0].toLong() and 0xFFFFFFFFL).toDouble()
            counts[kind]++
        }

        companion object {
            const val RING = 4
            const val KINDS = 4
            // Odd, so the frames timed alternate between the marble's turns.
            const val SAMPLE_EVERY = 7
            const val TIME_ELAPSED = 0x88BF
        }
    }

    companion object {
        private const val TAG = "TouchGlassUi"
        private const val EFFECTS_FULL = 0
        private const val EFFECTS_NO_SIM = 1
        private const val EFFECTS_FLAT = 2
        // Logs each frame that misses 120 Hz, with where its time went.
        private const val DEBUG_SLOW = false
        private const val HEADER = "#version 300 es\nprecision highp float;\nprecision highp int;\n"

        // 6 px per cell: fingers span dozens of cells and the ripples the
        // viscosity leaves are wider still, at a fifth of the cost of 4 px.
        // Upstream's is 6. Coarser means fewer cells and, as a wave then crosses
        // one more slowly, fewer substeps; with the glasses holding this screen
        // to 60 Hz each frame was running 10 of them.
        const val CELL = 8
        const val MAX_SUBSTEPS = 24
        const val MAX_THICKNESS = 3f
        const val KICK_S = 0.03f
        const val EDGE_BAND_DP = 12f
        // The oil running back where the pad grows: how long its eased
        // friction takes to fade, s; how much of the friction it loses at
        // first, at most; how unevenly, 0 to 1; and how big the patches that
        // run ahead or lag, dp.
        const val REFILL_TIME = 3f
        const val REFILL_EASE = 0.97f
        const val REFILL_VARY = 0.35f
        const val REFILL_SIZE_DP = 80f
        const val SETTLE_NANOS = 6_000_000_000L
        // Frames between keeping the oil's volume while nothing moves it in or out.
        const val KEEP_EVERY = 8
        // How long the controls must have been still before the rate drops.
        const val SCENE_SETTLE_NANOS = 1_500_000_000L
        // Frames shorter than this, s, are at the display's top rate.
        // How far the phone's turn may raise or lower the light, degrees.
        const val TILT_LIGHT_RANGE = 10f
        // How much of the set direction the light keeps, by its horizontal
        // part: enough to give an overhead light one.
        const val OVERHEAD_EASE = 0.02f
        // The design's dimming ellipse around the legend, dp of the design.
        const val CALM_RX = 186f
        const val CALM_RY = 150f
    }
}
