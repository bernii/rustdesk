package com.carriez.flutter_hbb.xr;

import android.opengl.GLSurfaceView;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * Asks the GPU to favor one GL context over another, where the driver offers it. The
 * glasses' view and the phone's glass share a GPU, and only the first is tied to a head.
 * A driver may grant less than was asked, so the level it gave is reported.
 */
public final class XrGlPriority implements GLSurfaceView.EGLContextFactory {
    public static final int HIGH = 0x3101, LOW = 0x3103;
    private static final int LEVEL = 0x3100; // EGL_CONTEXT_PRIORITY_LEVEL_IMG
    private static final int CLIENT_VERSION = 0x3098; // EGL_CONTEXT_CLIENT_VERSION
    private final int version, priority;
    /** What the driver granted: "high", "medium", "low", or "default" without the extension. */
    public volatile String granted = "default";

    public XrGlPriority(int version, int priority) {
        this.version = version;
        this.priority = priority;
    }

    @Override public EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig config) {
        String extensions = egl.eglQueryString(display, EGL10.EGL_EXTENSIONS);
        if (extensions != null && extensions.contains("EGL_IMG_context_priority")) {
            EGLContext context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
                    new int[]{CLIENT_VERSION, version, LEVEL, priority, EGL10.EGL_NONE});
            if (context != null && context != EGL10.EGL_NO_CONTEXT) {
                int[] level = new int[1];
                if (egl.eglQueryContext(display, context, LEVEL, level)) {
                    granted = level[0] == HIGH ? "high" : level[0] == LOW ? "low" : "medium";
                }
                return context;
            }
        }
        granted = "default";
        return egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
                new int[]{CLIENT_VERSION, version, EGL10.EGL_NONE});
    }

    @Override public void destroyContext(EGL10 egl, EGLDisplay display, EGLContext context) {
        egl.eglDestroyContext(display, context);
    }
}
