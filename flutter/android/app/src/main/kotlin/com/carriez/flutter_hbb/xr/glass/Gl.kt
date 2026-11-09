package com.carriez.flutter_hbb.xr.glass

import android.opengl.GLES30.*

/** A linked program with its active uniforms' locations, set by name. */
class Program(vertexSource: String, fragmentSource: String, name: String) {
    val id: Int = glCreateProgram()
    private val locations = HashMap<String, Int>()

    init {
        glAttachShader(id, compile(GL_VERTEX_SHADER, vertexSource, "$name.vert"))
        glAttachShader(id, compile(GL_FRAGMENT_SHADER, fragmentSource, "$name.frag"))
        glLinkProgram(id)
        val status = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "$name: ${glGetProgramInfoLog(id)}" }
        glGetProgramiv(id, GL_ACTIVE_UNIFORMS, status, 0)
        val size = IntArray(1)
        val type = IntArray(1)
        for (i in 0 until status[0]) {
            val uniform = glGetActiveUniform(id, i, size, 0, type, 0)
            // Arrays report as "name[0]"; they're set whole by their bare name.
            locations[uniform.removeSuffix("[0]")] = glGetUniformLocation(id, uniform)
        }
    }

    fun use() = glUseProgram(id)

    // Unused uniforms are optimised away; setting them is a no-op.
    private fun at(name: String) = locations[name] ?: -1

    fun set(name: String, v: Float) = at(name).let { if (it >= 0) glUniform1f(it, v) }
    fun set(name: String, x: Float, y: Float) = at(name).let { if (it >= 0) glUniform2f(it, x, y) }
    fun set(name: String, x: Float, y: Float, z: Float) = at(name).let { if (it >= 0) glUniform3f(it, x, y, z) }
    fun set(name: String, x: Float, y: Float, z: Float, w: Float) = at(name).let { if (it >= 0) glUniform4f(it, x, y, z, w) }
    fun setInt(name: String, v: Int) = at(name).let { if (it >= 0) glUniform1i(it, v) }
    fun setInt(name: String, x: Int, y: Int) = at(name).let { if (it >= 0) glUniform2i(it, x, y) }
    fun setUint(name: String, v: Int) = at(name).let { if (it >= 0) glUniform1ui(it, v) }
    fun setVec4s(name: String, data: FloatArray) = at(name).let { if (it >= 0) glUniform4fv(it, data.size / 4, data, 0) }
    fun setFloats(name: String, data: FloatArray) = at(name).let { if (it >= 0) glUniform1fv(it, data.size, data, 0) }

    private fun compile(type: Int, source: String, label: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        val status = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "$label: ${glGetShaderInfoLog(shader)}" }
        return shader
    }
}

/**
 * A texture with a framebuffer to render into it. RGBA32F isn't filterable
 * without an extension, so float state is read with texelFetch and only the
 * half-float and 8-bit targets filter linearly.
 */
class Target(val width: Int, val height: Int, format: Int = GL_RGBA8) {
    val texture: Int
    val framebuffer: Int

    init {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        texture = ids[0]
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexStorage2D(GL_TEXTURE_2D, 1, format, width, height)
        val filter = if (format == GL_RGBA32F) GL_NEAREST else GL_LINEAR
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            "Can't render to a ${width}×$height texture of format 0x${format.toString(16)}"
        }
    }

    /**
     * Every pass overwrites its whole target, so tell the driver the old
     * contents are garbage: a tiled GPU then skips reading them back from
     * memory at the start of the pass.
     */
    fun bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, COLOR_ATTACHMENT, 0)
        glViewport(0, 0, width, height)
    }

    fun clear() {
        bind()
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT)
    }

    fun release() {
        glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

/**
 * The composite's layers (glass.frag with LAYERS): two 8-bit textures drawn
 * in one pass, what multiplies the background and what's added on top.
 */
class Layers(val width: Int, val height: Int) {
    val scale: Int
    val add: Int
    private val framebuffer: Int

    init {
        val ids = IntArray(2)
        glGenTextures(2, ids, 0)
        scale = ids[0]
        add = ids[1]
        for (texture in ids) {
            glBindTexture(GL_TEXTURE_2D, texture)
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }
        glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, scale, 0)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, add, 0)
        glDrawBuffers(2, BOTH_ATTACHMENTS, 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Can't render the layers" }
    }

    fun bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glInvalidateFramebuffer(GL_FRAMEBUFFER, 2, BOTH_ATTACHMENTS, 0)
        glViewport(0, 0, width, height)
    }

    fun release() {
        glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        glDeleteTextures(2, intArrayOf(scale, add), 0)
    }

    private companion object {
        val BOTH_ATTACHMENTS = intArrayOf(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1)
    }
}

private val COLOR_ATTACHMENT = intArrayOf(GL_COLOR_ATTACHMENT0)
private val DEFAULT_COLOR = intArrayOf(GL_COLOR)

/** The screen, which the composite overwrites completely, like [Target.bind]. */
fun bindScreen(width: Int, height: Int) {
    glBindFramebuffer(GL_FRAMEBUFFER, 0)
    glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, DEFAULT_COLOR, 0)
    glViewport(0, 0, width, height)
}

fun bindTexture(unit: Int, texture: Int) {
    glActiveTexture(GL_TEXTURE0 + unit)
    glBindTexture(GL_TEXTURE_2D, texture)
}

/** One triangle covering the viewport; fullscreen.vert needs no vertex data. */
fun drawFullscreen() = glDrawArrays(GL_TRIANGLES, 0, 3)
