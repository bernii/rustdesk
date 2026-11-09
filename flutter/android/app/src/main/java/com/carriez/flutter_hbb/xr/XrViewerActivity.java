package com.carriez.flutter_hbb.xr;

import android.app.Activity;
import android.content.res.Configuration;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.Map;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class XrViewerActivity extends Activity implements GLSurfaceView.Renderer, Choreographer.FrameCallback {
    private XrController owner;
    private GLSurfaceView video;
    private XrOverlay overlay;
    private final XrProjectionRenderer projection = new XrProjectionRenderer();
    private volatile boolean anchored = true;
    private volatile boolean projectionReady;
    private long lastStatus;
    private volatile int surfaceWidth, surfaceHeight;
    private volatile boolean fit = true;
    private volatile float panX = .5f, panY = .5f;
    private volatile boolean ticking;
    private boolean fullscreenRequested;
    private long statsFrom, lastFrameStart, worstGap, busyTotal, busyWorst, uploadTotal, uploadWorst;
    private int statFrames, slowFrames, uploads;
    private volatile int thermalStatus;
    private volatile long mainLateWorst;
    volatile long framesDrawn;
    private android.os.HandlerThread pacer;
    private final XrGlPriority gpuPriority = new XrGlPriority(2, XrGlPriority.HIGH);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        owner = XrController.current.get();
        if (owner == null || !owner.accepts(this) || getWindowManager().getDefaultDisplay().getDisplayId() != owner.displayId) {
            finish(); return;
        }
        owner.viewer = this;
        anchored = !Boolean.FALSE.equals(owner.overlay.get("anchored"));
        owner.tracking(anchored);
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        fullscreen();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        video = new GLSurfaceView(this);
        video.setEGLContextClientVersion(2);
        video.setEGLContextFactory(gpuPriority);
        video.setRenderer(this);
        video.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        overlay = new XrOverlay(this, owner);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL || b - t != oldB - oldT) refresh();
        });
        refresh();
    }

    private void fullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            // Before setContentView there's no decor yet, and older Android throws rather than return null.
            getWindow().getDecorView();
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else getWindow().getDecorView().setSystemUiVisibility(5894);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused || video == null) return;
        fullscreen();
        if (Build.VERSION.SDK_INT >= 34 && isInMultiWindowMode() && !fullscreenRequested) {
            fullscreenRequested = true;
            requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER, new android.os.OutcomeReceiver<Void, Throwable>() {
                @Override public void onResult(Void result) { fullscreen(); }
                @Override public void onError(Throwable failure) {
                    // Keep a usable viewer if the desktop shell declines fullscreen.
                    owner.errorMessage("Android declined fullscreen; maximize the XR window in desktop mode.");
                }
            });
        }
    }

    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); fullscreen(); refresh(); }
    @Override protected void onResume() {
        super.onResume();
        if (video != null) {
            video.onResume(); ticking = true;
            owner.tracking(anchored);
            Choreographer.getInstance().postFrameCallback(this);
            startPacer();
        }
    }
    @Override protected void onPause() {
        ticking = false;
        stopPacer();
        if (owner != null && owner.viewer == this) owner.tracking(false);
        Choreographer.getInstance().removeFrameCallback(this);
        if (video != null) { XrRenderer.enable(false); video.onPause(); }
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (owner != null) owner.closed(this);
        super.onDestroy();
    }
    // Head-tracked frames are asked for from a thread of their own: the main thread also
    // has the phone's touches, views and channel messages, and a head moving can't wait for those.
    private void startPacer() {
        stopPacer();
        pacer = new android.os.HandlerThread("xr-pacer", android.os.Process.THREAD_PRIORITY_DISPLAY);
        pacer.start();
        new android.os.Handler(pacer.getLooper()).post(() -> {
            Choreographer choreographer = Choreographer.getInstance();
            choreographer.postFrameCallback(new Choreographer.FrameCallback() {
                @Override public void doFrame(long time) {
                    if (!ticking || video == null) return;
                    if (anchored || XrRenderer.pending()) video.requestRender();
                    choreographer.postFrameCallback(this);
                }
            });
        });
    }
    private void stopPacer() {
        if (pacer != null) pacer.quit();
        pacer = null;
    }
    @Override public void doFrame(long time) {
        if (!ticking || video == null) return;
        // Only the status report stays here, and how late this runs says how busy the main thread is.
        long late = System.nanoTime() - time;
        if (late > mainLateWorst) mainLateWorst = late;
        if (anchored && time - lastStatus >= 500_000_000L) {
            lastStatus = time; owner.reportTracking();
            if (Build.VERSION.SDK_INT >= 29) {
                android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (power != null) thermalStatus = power.getCurrentThermalStatus();
            }
        }
        Choreographer.getInstance().postFrameCallback(this);
    }
    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        projectionReady = false;
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        boolean ok = XrRenderer.initialize();
        XrRenderer.enable(ok);
        runOnUiThread(() -> {
            if (!owner.accepts(this) || isFinishing()) return;
            if (ok) owner.ready(this);
            else owner.failed("The XR video renderer could not start. Returned to the phone screen.");
        });
    }
    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = width; surfaceHeight = height;
        runOnUiThread(() -> { if (owner.viewer == this) owner.report(); });
    }
    @Override public void onDrawFrame(GL10 gl) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return;
        long frameStart = System.nanoTime();
        framesDrawn++;
        Xreal3Dof tracker = owner.tracker;
        boolean ok = anchored
                ? initializeProjection() && tracker != null && projection.draw(surfaceWidth, surfaceHeight, tracker)
                : XrRenderer.draw(surfaceWidth, surfaceHeight, fit, panX, panY);
        if (!ok) {
            XrRenderer.enable(false);
            runOnUiThread(() -> {
                if (owner.viewer == this) owner.failed("XR video upload failed. Returned to the phone screen.");
            });
        }
        frameStats(frameStart, System.nanoTime(), XrRenderer.takeUploadMicros());
    }

    // What the wearer sees. Once a second, as it can't be read from outside: the
    // compositor's --latency has nothing for these surfaces on Android 17.
    private void frameStats(long start, long end, long uploadMicros) {
        if (statsFrom == 0) statsFrom = start;
        if (lastFrameStart != 0) {
            long gap = start - lastFrameStart;
            worstGap = Math.max(worstGap, gap);
            if (gap > 25_000_000L) slowFrames++;
        }
        lastFrameStart = start;
        statFrames++;
        long busy = end - start;
        busyTotal += busy;
        busyWorst = Math.max(busyWorst, busy);
        if (uploadMicros > 0) {
            uploads++;
            uploadTotal += uploadMicros;
            uploadWorst = Math.max(uploadWorst, uploadMicros);
        }
        if (start - statsFrom < 1_000_000_000L) return;
        android.util.Log.i("RustDeskXR", String.format(java.util.Locale.ROOT,
                "projection %d fps, worst gap %.1f ms, over 25 ms: %d · draw avg %.1f worst %.1f ms · uploads %d avg %.1f worst %.1f ms · "
                        + "pose stale %d age worst %.1f ms imu %.0f Hz · head %.0f deg/s · main late %.1f ms · gpu %s · thermal %d",
                statFrames, worstGap / 1e6, slowFrames, busyTotal / 1e6 / statFrames, busyWorst / 1e6,
                uploads, uploads == 0 ? 0 : uploadTotal / 1e3 / uploads, uploadWorst / 1e3,
                projection.staleFrames, projection.worstPoseAgeMs, projection.imuHz, projection.worstHeadDegPerSecond,
                mainLateWorst / 1e6, gpuPriority.granted, thermalStatus));
        projection.staleFrames = 0;
        projection.worstPoseAgeMs = projection.worstHeadDegPerSecond = 0;
        mainLateWorst = 0;
        statsFrom = start;
        worstGap = busyTotal = busyWorst = uploadTotal = uploadWorst = 0;
        statFrames = slowFrames = uploads = 0;
    }
    private boolean initializeProjection() {
        if (projectionReady) return true;
        try {
            projection.initialize(); projectionReady = true;
            return true;
        } catch (RuntimeException failure) {
            android.util.Log.e("RustDeskXR", "3DoF shader initialization failed", failure);
            return false;
        }
    }
    void refresh() {
        if (video == null) return;
        boolean nextAnchored = !Boolean.FALSE.equals(owner.overlay.get("anchored"));
        if (ticking) owner.tracking(nextAnchored);
        anchored = nextAnchored;
        projection.configure(owner.overlay);
        fit = !Boolean.FALSE.equals(owner.overlay.get("fit"));
        panX = number("panX", .5f); panY = number("panY", .5f);
        configureBuffer();
        overlay.refresh(); video.requestRender();
    }
    /** In 3DoF the cursor is part of the projection, so the window's views have nothing to redraw. */
    void pointerMoved() {
        if (video == null) return;
        if (!anchored) { refresh(); return; }
        projection.configure(owner.overlay);
        video.requestRender();
    }
    private void configureBuffer() {
        FrameLayout root = (FrameLayout) video.getParent();
        int width = (int) number("width", 0), height = (int) number("height", 0);
        boolean fullSource = !anchored && fit && Boolean.TRUE.equals(owner.overlay.get("fullSourceBuffer"))
                && width > 0 && height > 0 && width <= 16384 && height <= 16384;
        int viewWidth = -1, viewHeight = -1;
        if (fullSource && root.getWidth() > 0 && root.getHeight() > 0) {
            // A fixed buffer is stretched to the SurfaceView bounds by Android.
            // Match the source aspect ratio so the compositor cannot distort it.
            float scale = Math.min(root.getWidth() / (float) width, root.getHeight() / (float) height);
            viewWidth = Math.max(1, Math.round(width * scale));
            viewHeight = Math.max(1, Math.round(height * scale));
        }
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) video.getLayoutParams();
        if (layout.width != viewWidth || layout.height != viewHeight || layout.gravity != Gravity.CENTER) {
            video.setLayoutParams(new FrameLayout.LayoutParams(viewWidth, viewHeight, Gravity.CENTER));
        }
        if (fullSource) video.getHolder().setFixedSize(width, height);
        else video.getHolder().setSizeFromLayout();
    }
    private float number(String key, float fallback) {
        Object value = owner.overlay.get(key);
        return value instanceof Number ? ((Number) value).floatValue() : fallback;
    }
    void describe(Map<String, Object> values) {
        if (Build.VERSION.SDK_INT < 23) return;
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode mode = display.getMode();
        values.put("outputWidth", mode.getPhysicalWidth()); values.put("outputHeight", mode.getPhysicalHeight());
        values.put("refreshRate", (double) mode.getRefreshRate());
        values.put("surfaceWidth", surfaceWidth); values.put("surfaceHeight", surfaceHeight);
        values.put("viewportWidth", overlay.getWidth()); values.put("viewportHeight", overlay.getHeight());
        values.put("projection", !anchored ? "Flat" : Boolean.TRUE.equals(owner.overlay.get("motionPrediction")) ? "3DoF · prediction on" : "3DoF · prediction off");
        values.put("density", (double) getResources().getDisplayMetrics().density);
    }
}
