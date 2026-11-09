#include <GLES2/gl2.h>
#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace {
std::mutex mutex;
std::vector<uint8_t> pending;
int pending_width = 0, pending_height = 0;
bool enabled = false, dirty = false;
// Only the GLSurfaceView render thread owns these objects. Context recreation resets them.
std::vector<uint8_t> uploading;
GLuint program = 0, texture = 0;
int frame_width = 0, frame_height = 0;

GLuint shader(GLenum type, const char* source) {
    GLuint id = glCreateShader(type);
    glShaderSource(id, 1, &source, nullptr);
    glCompileShader(id);
    GLint ok = 0;
    glGetShaderiv(id, GL_COMPILE_STATUS, &ok);
    if (!ok) { glDeleteShader(id); return 0; }
    return id;
}
}

// The core owns bytes until sessionNextRgba. Copy synchronously into a bounded
// latest-frame mailbox; neither the Java channel nor the GL thread retains that pointer.
extern "C" __attribute__((visibility("default")))
int xr_submit_frame(const uint8_t* bytes, intptr_t length, int width, int height) {
    if (!bytes || width <= 0 || height <= 0 || width > 16384 || height > 16384 ||
        static_cast<int64_t>(width) * height * 4 != length) return 0;
    std::lock_guard<std::mutex> guard(mutex);
    if (!enabled) return 0;
    pending.resize(static_cast<size_t>(length));
    std::memcpy(pending.data(), bytes, static_cast<size_t>(length));
    pending_width = width;
    pending_height = height;
    dirty = true;
    return 1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_carriez_flutter_1hbb_xr_XrRenderer_enable(JNIEnv*, jclass, jboolean value) {
    std::lock_guard<std::mutex> guard(mutex);
    enabled = value;
    if (!enabled) {
        dirty = false;
        std::vector<uint8_t>().swap(pending);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_carriez_flutter_1hbb_xr_XrRenderer_pending(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(mutex);
    return enabled && dirty;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_carriez_flutter_1hbb_xr_XrRenderer_initialize(JNIEnv*, jclass) {
    frame_width = frame_height = 0;
    program = texture = 0;
    const char* vertex = "attribute vec2 position; attribute vec2 uv; varying vec2 tex;"
                         "void main(){ gl_Position=vec4(position,0.,1.); tex=uv; }";
    const char* fragment = "precision mediump float; varying vec2 tex; uniform sampler2D frame;"
                           "void main(){ gl_FragColor=vec4(texture2D(frame,tex).bgr,1.); }";
    GLuint vs = shader(GL_VERTEX_SHADER, vertex), fs = shader(GL_FRAGMENT_SHADER, fragment);
    if (!vs || !fs) {
        glDeleteShader(vs); glDeleteShader(fs);
        return false;
    }
    program = glCreateProgram();
    glAttachShader(program, vs); glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "position");
    glBindAttribLocation(program, 1, "uv");
    glLinkProgram(program);
    glDeleteShader(vs); glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) { glDeleteProgram(program); program = 0; return false; }
    glUseProgram(program);
    glUniform1i(glGetUniformLocation(program, "frame"), 0);
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return glGetError() == GL_NO_ERROR;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_carriez_flutter_1hbb_xr_XrRenderer_draw(JNIEnv*, jclass, jint width, jint height,
        jboolean fit, jfloat pan_x, jfloat pan_y) {
    if (!program || width <= 0 || height <= 0) return false;
    int w = 0, h = 0;
    {
        std::lock_guard<std::mutex> guard(mutex);
        if (!enabled) return true;
        if (dirty) {
            uploading.swap(pending);
            w = pending_width; h = pending_height;
            dirty = false;
        }
    }
    glBindTexture(GL_TEXTURE_2D, texture);
    if (w > 0) {
        GLint maximum = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maximum);
        if (w > maximum || h > maximum) return false;
        if (frame_width != w || frame_height != h) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                        GL_UNSIGNED_BYTE, uploading.data());
            frame_width = w; frame_height = h;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA,
                           GL_UNSIGNED_BYTE, uploading.data());
        }
    }
    glViewport(0, 0, width, height);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!frame_width || !frame_height) return true;
    float scale = fit ? std::min(float(width) / frame_width, float(height) / frame_height) : 1.f;
    float sw = frame_width * scale, sh = frame_height * scale;
    float left = sw <= width ? (width - sw) / 2 : -(sw - width) * pan_x;
    float top = sh <= height ? (height - sh) / 2 : -(sh - height) * pan_y;
    float l = 2 * left / width - 1, r = 2 * (left + sw) / width - 1;
    float t = 1 - 2 * top / height, b = 1 - 2 * (top + sh) / height;
    const float vertices[] = {l,b,0,1, r,b,1,1, l,t,0,0, r,t,1,0};
    glUseProgram(program);
    glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), vertices);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), vertices + 2);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    return glGetError() == GL_NO_ERROR;
}

// Only touched on the GL thread.
static jlong upload_micros = 0;

extern "C" JNIEXPORT jlong JNICALL
Java_com_carriez_flutter_1hbb_xr_XrRenderer_takeUploadMicros(JNIEnv*, jclass) {
    const jlong value = upload_micros;
    upload_micros = 0;
    return value;
}

// Upload on the owning GL thread before Java takes the late pose snapshot.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_carriez_flutter_1hbb_xr_XrRenderer_prepareProjection(JNIEnv* env, jclass) {
    if (!program) return nullptr;
    int w = 0, h = 0;
    {
        std::lock_guard<std::mutex> guard(mutex);
        if (!enabled) return env->NewIntArray(3);
        if (dirty) {
            uploading.swap(pending);
            w = pending_width; h = pending_height;
            dirty = false;
        }
    }
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    if (w > 0) {
        const auto upload_start = std::chrono::steady_clock::now();
        GLint maximum = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maximum);
        if (w > maximum || h > maximum) return nullptr;
        if (frame_width != w || frame_height != h) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                        GL_UNSIGNED_BYTE, uploading.data());
            frame_width = w; frame_height = h;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA,
                           GL_UNSIGNED_BYTE, uploading.data());
        }
        upload_micros = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - upload_start).count();
    }
    if (glGetError() != GL_NO_ERROR) return nullptr;
    const jint frame[] = {static_cast<jint>(texture), frame_width, frame_height};
    jintArray result = env->NewIntArray(3);
    if (result) env->SetIntArrayRegion(result, 0, 3, frame);
    return result;
}
