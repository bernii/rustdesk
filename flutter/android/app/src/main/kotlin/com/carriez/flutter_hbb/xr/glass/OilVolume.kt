package com.carriez.flutter_hbb.xr.glass

import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Keeps the oil's volume where it was when the oil was laid down at rest
 * (pad_volume.frag): once a frame it sums the oil on the GPU and spreads
 * back over the puddle whatever is missing or extra. Nothing is read back
 * to the CPU for that; only the stats read the sum, once a second, without
 * waiting for the GPU.
 */
class OilVolume(build: (name: String, chunks: List<String>) -> Program) {
    private val program = build("pad_volume", listOf("common.glsl", "sim.glsl", "pad_volume.frag"))

    // Each level a quarter the size of the one below, down to 1 × 1.
    private val levels = ArrayList<Target>()
    private lateinit var sum: Target
    private lateinit var target: Target

    // The stats' readback: a pixel buffer, and a fence for when it's filled.
    private var buffer = 0
    private var fence = 0L
    private var asked = false

    /** The oil's volume as a share of what's kept, as last read; NaN until the first read. */
    var share = Float.NaN
        private set

    /** Allocates its targets for a simW × simH grid; [target] registers each. */
    fun resize(simW: Int, simH: Int, make: (Int, Int, Int) -> Target) {
        levels.clear()
        var w = simW
        var h = simH
        while (w > 1 || h > 1) {
            w = ceil(w / BLOCK.toFloat()).toInt()
            h = ceil(h / BLOCK.toFloat()).toInt()
            levels += make(w, h, GL_RGBA32F)
        }
        sum = levels.last()
        target = make(1, 1, GL_RGBA32F)
        program.use()
        program.setInt("uState", 0)
        program.setInt("uRest", 1)
        program.setInt("uBlocks", 2)
        program.setInt("uSum", 3)
        program.setInt("uTarget", 4)
        if (buffer == 0) {
            val ids = IntArray(1)
            glGenBuffers(1, ids, 0)
            buffer = ids[0]
            glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer)
            glBufferData(GL_PIXEL_PACK_BUFFER, 16, null, GL_STREAM_READ)
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        }
        asked = false
        share = Float.NaN
    }

    /** The volume to keep from now on: the oil in [state] on [rest]. */
    fun rebase(state: Target, rest: Target, zero: Target) {
        measure(state, rest, zero, into = target)
    }

    /** Puts back what [state] has lost, or takes what it has gained, swapping it. */
    fun keep(state: Array<Target>, rest: Target) {
        measure(state[0], rest, target, into = sum)
        program.setInt("uPass", 2)
        bindTexture(0, state[0].texture)
        bindTexture(1, rest.texture)
        bindTexture(3, sum.texture)
        state[1].bind()
        drawFullscreen()
        val first = state[0]
        state[0] = state[1]
        state[1] = first
    }

    private fun measure(state: Target, rest: Target, kept: Target, into: Target) {
        program.use()
        program.setInt("uPass", 0)
        program.setInt("uWithTarget", 0)
        bindTexture(0, state.texture)
        bindTexture(1, rest.texture)
        bindTexture(4, kept.texture)
        for (i in levels.indices) {
            val last = i == levels.lastIndex
            if (i > 0) {
                program.setInt("uPass", 1)
                bindTexture(2, levels[i - 1].texture)
            }
            if (last) program.setInt("uWithTarget", if (into === target) 0 else 1)
            (if (last) into else levels[i]).bind()
            drawFullscreen()
        }
    }

    /**
     * For the stats: asks for the last sum to be copied out, and picks up the
     * one asked for last time if the GPU has finished it. Call about once a
     * second. The sum is the oil before [keep] last put any back.
     */
    fun read() {
        if (asked) {
            val status = glClientWaitSync(fence, 0, 0)
            if (status == GL_TIMEOUT_EXPIRED) return
            glDeleteSync(fence)
            glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer)
            val mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, 16, GL_MAP_READ_BIT) as ByteBuffer?
            if (mapped != null) {
                val floats = mapped.order(ByteOrder.nativeOrder()).asFloatBuffer()
                val kept = floats.get(3)
                if (kept > 0f) share = floats.get(0) / kept
                glUnmapBuffer(GL_PIXEL_PACK_BUFFER)
            }
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
            asked = false
        }
        glBindFramebuffer(GL_FRAMEBUFFER, sum.framebuffer)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer)
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_FLOAT, 0)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        asked = true
    }

    private companion object {
        const val BLOCK = 4 // pad_volume.frag's
    }
}
