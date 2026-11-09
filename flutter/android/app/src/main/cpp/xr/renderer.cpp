#include <GLES2/gl2.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <sys/system_properties.h>
#include <time.h>
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

// Window-switch latency probe, off unless `adb shell setprop debug.rustdesk.xr.latency 1`.
// Samples a 32×18 grid of the decoded BGRA frame and logs the wall-clock time each
// time its mean brightness crosses between dark (<70) and light (>170). Pair with a
// host-side stimulus that logs when it flips the screen; the difference (after clock
// alignment) is capture+encode+network+decode up to this handoff, not photons.
int probe_state = 0;  // 0 unknown, 1 dark, 2 light
void latency_probe(const uint8_t* bytes, int width, int height) {
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.rustdesk.xr.latency", value) <= 0 || value[0] != '1') {
        probe_state = 0;
        return;
    }
    uint64_t sum = 0;
    for (int gy = 0; gy < 18; ++gy) {
        const int y = (2 * gy + 1) * height / 36;
        for (int gx = 0; gx < 32; ++gx) {
            const uint8_t* p = bytes + (static_cast<size_t>(y) * width + (2 * gx + 1) * width / 64) * 4;
            sum += (p[0] + 2u * p[1] + p[2]) / 4u;
        }
    }
    const int luma = static_cast<int>(sum / (32 * 18));
    const int state = luma < 70 ? 1 : luma > 170 ? 2 : probe_state;
    if (state != probe_state) {
        timespec now{};
        clock_gettime(CLOCK_REALTIME, &now);
        __android_log_print(ANDROID_LOG_INFO, "RustDeskXRLatency", "flip %s luma %d at %lld.%03ld",
                            state == 1 ? "dark" : "light", luma, static_cast<long long>(now.tv_sec),
                            now.tv_nsec / 1000000);
        probe_state = state;
    }
}

// MediaCodec frames handed over as YUV by the Rust core (`rustdesk_xr_set_yuv_sink`) and
// converted on the GPU into `texture`, in the same BGRA byte order a CPU upload would give.
// This skips the CPU NV12->BGRA conversion, the 33 MB copies and the Dart hop at 4K.
std::vector<uint8_t> pending_yuv, filling_yuv;  // packed Y, then interleaved UV
int yuv_width = 0, yuv_height = 0;
bool yuv_dirty = false, yuv_seen = false;
// GL thread only.
std::vector<uint8_t> uploading_yuv;
GLuint yuv_program = 0, y_texture = 0, uv_texture = 0, framebuffer = 0;
int y_width = 0, y_height = 0;

void latency_probe_luma(const uint8_t* y_plane, int stride, int width, int height) {
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.rustdesk.xr.latency", value) <= 0 || value[0] != '1') {
        probe_state = 0;
        return;
    }
    uint64_t sum = 0;
    for (int gy = 0; gy < 18; ++gy) {
        const uint8_t* row = y_plane + static_cast<size_t>((2 * gy + 1) * height / 36) * stride;
        for (int gx = 0; gx < 32; ++gx) sum += row[(2 * gx + 1) * width / 64];
    }
    const int luma = static_cast<int>(sum / (32 * 18));
    const int state = luma < 70 ? 1 : luma > 170 ? 2 : probe_state;
    if (state != probe_state) {
        timespec now{};
        clock_gettime(CLOCK_REALTIME, &now);
        __android_log_print(ANDROID_LOG_INFO, "RustDeskXRLatency", "flip %s luma %d at %lld.%03ld",
                            state == 1 ? "dark" : "light", luma, static_cast<long long>(now.tv_sec),
                            now.tv_nsec / 1000000);
        probe_state = state;
    }
}

// Called on the core's video thread while it still owns the codec buffer.
int submit_yuv(const uint8_t* y, int y_stride, const uint8_t* u, const uint8_t* v, int uv_stride,
               int uv_pixel_stride, int width, int height) {
    if (!y || !u || !v || width <= 0 || height <= 0 || width > 16384 || height > 16384 ||
        y_stride < width || uv_pixel_stride < 1) return 0;
    {
        std::lock_guard<std::mutex> guard(mutex);
        if (!enabled) return 0;
    }
    const int cw = (width + 1) / 2, ch = (height + 1) / 2;
    const size_t y_size = static_cast<size_t>(width) * height;
    filling_yuv.resize(y_size + static_cast<size_t>(cw) * ch * 2);
    uint8_t* out = filling_yuv.data();
    for (int r = 0; r < height; ++r) std::memcpy(out + static_cast<size_t>(r) * width, y + static_cast<size_t>(r) * y_stride, width);
    for (int r = 0; r < ch; ++r) {
        const uint8_t* ur = u + static_cast<size_t>(r) * uv_stride;
        const uint8_t* vr = v + static_cast<size_t>(r) * uv_stride;
        uint8_t* o = out + y_size + static_cast<size_t>(r) * cw * 2;
        if (uv_pixel_stride == 2 && vr == ur + 1) {
            std::memcpy(o, ur, static_cast<size_t>(cw) * 2);
        } else {
            for (int c = 0; c < cw; ++c) {
                o[2 * c] = ur[c * uv_pixel_stride];
                o[2 * c + 1] = vr[c * uv_pixel_stride];
            }
        }
    }
    std::lock_guard<std::mutex> guard(mutex);
    if (!enabled) return 0;
    latency_probe_luma(out, width, width, height);
    pending_yuv.swap(filling_yuv);
    yuv_width = width;
    yuv_height = height;
    yuv_dirty = yuv_seen = true;
    return 1;
}

void register_yuv_sink(bool on) {
    void* core = dlopen("librustdesk.so", RTLD_NOW | RTLD_NOLOAD);
    if (!core) return;
    using SetSink = void (*)(decltype(&submit_yuv));
    if (auto set = reinterpret_cast<SetSink>(dlsym(core, "rustdesk_xr_set_yuv_sink"))) {
        set(on ? &submit_yuv : nullptr);
    }
    dlclose(core);
}

bool create_yuv_program() {
    const char* vertex = "attribute vec2 position; varying vec2 tex;"
                         "void main(){ gl_Position=vec4(position,0.,1.); tex=position*0.5+0.5; }";
    // BT.601 limited range, matching libyuv's NV12ToARGB used on the CPU path.
    const char* fragment =
        "precision mediump float; varying vec2 tex; uniform sampler2D luma, chroma;"
        "void main(){ float y=1.164383*(texture2D(luma,tex).r-0.062745);"
        " vec2 c=texture2D(chroma,tex).ra-0.501961;"
        " vec3 rgb=vec3(y+1.596027*c.y, y-0.391762*c.x-0.812968*c.y, y+2.017232*c.x);"
        " gl_FragColor=vec4(clamp(rgb.bgr,0.,1.),1.); }";
    GLuint vs = shader(GL_VERTEX_SHADER, vertex), fs = shader(GL_FRAGMENT_SHADER, fragment);
    if (!vs || !fs) {
        glDeleteShader(vs); glDeleteShader(fs);
        return false;
    }
    yuv_program = glCreateProgram();
    glAttachShader(yuv_program, vs); glAttachShader(yuv_program, fs);
    glBindAttribLocation(yuv_program, 0, "position");
    glLinkProgram(yuv_program);
    glDeleteShader(vs); glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(yuv_program, GL_LINK_STATUS, &ok);
    if (!ok) { glDeleteProgram(yuv_program); yuv_program = 0; return false; }
    glUseProgram(yuv_program);
    glUniform1i(glGetUniformLocation(yuv_program, "luma"), 0);
    glUniform1i(glGetUniformLocation(yuv_program, "chroma"), 1);
    GLuint ids[2];
    glGenTextures(2, ids);
    y_texture = ids[0]; uv_texture = ids[1];
    for (GLuint id : ids) {
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, id == y_texture ? GL_NEAREST : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, id == y_texture ? GL_NEAREST : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    glGenFramebuffers(1, &framebuffer);
    y_width = y_height = 0;
    return glGetError() == GL_NO_ERROR;
}

// GL thread: renders the newest YUV frame into `texture` and sets its size.
bool convert_pending_yuv() {
    int w = 0, h = 0;
    {
        std::lock_guard<std::mutex> guard(mutex);
        if (!enabled || !yuv_dirty) return true;
        uploading_yuv.swap(pending_yuv);
        w = yuv_width; h = yuv_height;
        yuv_dirty = false;
    }
    if (!texture || (!yuv_program && !create_yuv_program())) return false;
    GLint maximum = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maximum);
    if (w > maximum || h > maximum) return false;
    const int cw = (w + 1) / 2, ch = (h + 1) / 2;
    const uint8_t* plane = uploading_yuv.data();
    GLint previous_framebuffer = 0, previous_buffer = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &previous_framebuffer);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &previous_buffer);
    const GLboolean blend = glIsEnabled(GL_BLEND), scissor = glIsEnabled(GL_SCISSOR_TEST);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    if (frame_width != w || frame_height != h) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        frame_width = w; frame_height = h;
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    const bool resized = y_width != w || y_height != h;
    glBindTexture(GL_TEXTURE_2D, y_texture);
    if (resized) glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, w, h, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, plane);
    else glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_LUMINANCE, GL_UNSIGNED_BYTE, plane);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, uv_texture);
    plane += static_cast<size_t>(w) * h;
    if (resized) glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA, cw, ch, 0, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, plane);
    else glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, cw, ch, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, plane);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    y_width = w; y_height = h;

    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (ok) {
        static const float quad[] = {-1, -1, 1, -1, -1, 1, 1, 1};
        glViewport(0, 0, w, h);
        glDisable(GL_BLEND); glDisable(GL_SCISSOR_TEST);
        glUseProgram(yuv_program);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, quad);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, previous_framebuffer);
    glBindBuffer(GL_ARRAY_BUFFER, previous_buffer);
    if (blend) glEnable(GL_BLEND);
    if (scissor) glEnable(GL_SCISSOR_TEST);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    glUseProgram(program);
    return glGetError() == GL_NO_ERROR && ok;
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
    if (yuv_seen) return 1;
    latency_probe(bytes, width, height);
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
    register_yuv_sink(enabled);
    if (!enabled) {
        dirty = false;
        std::vector<uint8_t>().swap(pending);
        yuv_dirty = yuv_seen = false;
        std::vector<uint8_t>().swap(pending_yuv);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_carriez_flutter_1hbb_xr_XrRenderer_pending(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(mutex);
    return enabled && (dirty || yuv_dirty);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_carriez_flutter_1hbb_xr_XrRenderer_initialize(JNIEnv*, jclass) {
    frame_width = frame_height = 0;
    program = texture = 0;
    yuv_program = y_texture = uv_texture = framebuffer = 0;
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
    if (!convert_pending_yuv()) return false;
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
    if (!convert_pending_yuv()) return nullptr;
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
