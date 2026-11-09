package com.carriez.flutter_hbb.xr;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import com.carriez.flutter_hbb.xr.glass.SavedSettings;
import com.carriez.flutter_hbb.xr.glass.XrGlassHost;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

public final class XrController implements DisplayManager.DisplayListener {
    private static final int MIC_REQUEST = 7041;
    static WeakReference<XrController> current = new WeakReference<>(null);
    final Activity phone;
    final Handler handler = new Handler(Looper.getMainLooper());
    final DisplayManager displays;
    final MethodChannel channel;
    private final SharedPreferences preferences;
    private final DictationController speech;
    private final SavedSettings screen;
    XrGlassHost glass;
    String session;
    private String pendingSpeechSession;
    private int speechGeneration;
    int displayId = -1;
    XrViewerActivity viewer;
    volatile Xreal3Dof tracker;
    private boolean tracking;
    Map<String, Object> overlay = new HashMap<>();
    private boolean foreground = true, opening, pausedByUser, active;
    private String error = "";
    private final Runnable openLater = this::openViewer;

    public XrController(Activity phone, BinaryMessenger messenger) {
        this.phone = phone;
        displays = (DisplayManager) phone.getSystemService(Context.DISPLAY_SERVICE);
        preferences = phone.getSharedPreferences("rustdesk-xr", Context.MODE_PRIVATE);
        screen = new SavedSettings(phone);
        channel = new MethodChannel(messenger, "rustdesk-xr");
        speech = new DictationController(phone, new DictationController.Sink() {
            public void text(String value, boolean complete) {
                Map<String, Object> args = new HashMap<>();
                args.put("generation", speechGeneration);
                args.put("text", value); args.put("complete", complete);
                event("speech", args);
            }
            public void status(String value, boolean listening) {
                Map<String, Object> args = new HashMap<>();
                args.put("generation", speechGeneration);
                args.put("status", value); args.put("active", listening);
                event("speechStatus", args);
            }
            public void listening(boolean value) {
                Map<String, Object> args = new HashMap<>(); args.put("listening", value);
                args.put("generation", speechGeneration);
                event("listening", args);
            }
        });
        current = new WeakReference<>(this);
        channel.setMethodCallHandler(this::call);
        displays.registerDisplayListener(this, handler);
    }

    private void call(MethodCall call, MethodChannel.Result result) {
        if ("begin".equals(call.method)) {
            closeViewer();
            session = call.argument("session");
            pausedByUser = false;
            error = "";
            screenSettings(screen.getDistance(), screen.getCurvature(), screen.getMotionPrediction());
            schedule();
            result.success(state());
            return;
        }
        if (session == null || !session.equals(call.argument("session"))) {
            result.success(null);
            return;
        }
        switch (call.method) {
            case "end": closeViewer(); closeGlass(); session = null; overlay.clear(); break;
            case "enabled":
                preferences.edit().putBoolean("enabled", Boolean.TRUE.equals(call.argument("value"))).apply();
                pausedByUser = false;
                closeViewer(); schedule(); report();
                break;
            case "reopen": pausedByUser = false; closeViewer(); schedule(); break;
            case "overlay":
                Map<String, Object> values = call.argument("values");
                boolean pointerOnly = values != null && onlyPointerChanged(values);
                if (values != null) overlay.putAll(values);
                if (viewer != null) {
                    if (pointerOnly) viewer.pointerMoved(); else viewer.refresh();
                }
                if (glass != null && !pointerOnly) glass.update(overlay);
                break;
            case "trackpad":
                if (Boolean.TRUE.equals(call.argument("closed"))) closeGlass();
                else showGlass(Boolean.TRUE.equals(call.argument("visible")));
                break;
            case "trackpadKeyboard": if (glass != null) glass.showKeyboard(); break;
            case "trackpadBack": result.success(glass != null && glass.back()); return;
            case "recenter": if (tracker != null) tracker.recenter(); break;
            case "recheckCalibration": if (tracker != null && tracking) tracker.recheckCalibration(); break;
            case "calibrateStationary": if (tracker != null && tracking) tracker.calibrateStationary(); break;
            case "speechStart":
                if (Build.VERSION.SDK_INT >= 29 && active && foreground) {
                    Number generation = call.argument("generation");
                    speechGeneration = generation == null ? 0 : generation.intValue();
                    if (phone.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        pendingSpeechSession = session;
                        phone.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
                    } else speech.start();
                }
                break;
            case "speechStop": pendingSpeechSession = null; speech.stop(); break;
            case "speechCancel": pendingSpeechSession = null; speech.cancel(); break;
            case "sensitivity":
                Number value = call.argument("value");
                if (value != null) preferences.edit().putFloat("sensitivity", value.floatValue()).apply();
                break;
            default: result.notImplemented(); return;
        }
        result.success(null);
    }

    public void permissionResult(int requestCode, int[] grants) {
        if (requestCode != MIC_REQUEST || session == null || !session.equals(pendingSpeechSession) || !active || !foreground) return;
        pendingSpeechSession = null;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) speech.start();
        else {
            Map<String, Object> args = new HashMap<>();
            args.put("generation", speechGeneration);
            args.put("status", "Microphone permission denied. Use the keyboard instead.");
            args.put("active", false); event("speechStatus", args);
        }
    }

    /** This arrives with every pointer move, and most of what it can touch didn't change. */
    private boolean onlyPointerChanged(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!"x".equals(key) && !"y".equals(key) && !java.util.Objects.equals(entry.getValue(), overlay.get(key))) return false;
        }
        return true;
    }

    /** The trackpad draws with GLES 3 and paces itself with API 30's Surface.setFrameRate. */
    private boolean trackpadSupported() {
        if (Build.VERSION.SDK_INT < 30) return false;
        ActivityManager manager = phone.getSystemService(ActivityManager.class);
        return manager != null && manager.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
    }

    private void showGlass(boolean visible) {
        if (Build.VERSION.SDK_INT < 30) return;
        if (glass == null && visible) {
            glass = new XrGlassHost(phone, new XrGlassHost.Events() {
                @Override public void action(Map<String, Object> args) { event("trackpad", args); }
                @Override public void failed(String message) { XrController.this.failed(message); }
                @Override public void screen(float distance, float curvature, boolean prediction) {
                    screenSettings(distance, curvature, prediction);
                    if (viewer != null) viewer.refresh();
                }
            }, preferences.getFloat("sensitivity", 3));
            glass.update(overlay);
            glass.update(state());
        }
        if (glass != null) glass.show(visible);
    }

    private void closeGlass() {
        if (glass == null) return;
        glass.close();
        glass = null;
    }

    private void screenSettings(float distance, float curvature, boolean prediction) {
        overlay.put("distance", distance);
        overlay.put("curvature", curvature);
        overlay.put("motionPrediction", prediction);
    }

    private Display externalDisplay() {
        if (!trackpadSupported()) return null;
        for (Display display : displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (display.isValid() && display.getDisplayId() != Display.DEFAULT_DISPLAY) return display;
        }
        return null;
    }

    private void schedule() {
        handler.removeCallbacks(openLater);
        if (foreground && session != null && enabled() && !pausedByUser && viewer == null && !opening
                && externalDisplay() != null) handler.postDelayed(openLater, 1000);
    }

    private void openViewer() {
        if (Build.VERSION.SDK_INT < 29) return;
        Display display = externalDisplay();
        if (!foreground || session == null || !enabled() || pausedByUser || display == null || viewer != null) return;
        displayId = display.getDisplayId();
        Intent intent = new Intent(phone, XrViewerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra("session", session).putExtra("display", displayId);
        try {
            opening = true;
            phone.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
        } catch (RuntimeException failure) {
            failed("Cannot open the external display: " + failure.getClass().getSimpleName());
        }
    }

    boolean accepts(XrViewerActivity activity) {
        return foreground && session != null && session.equals(activity.getIntent().getStringExtra("session"))
                && enabled() && !pausedByUser && displayId != Display.DEFAULT_DISPLAY
                && displayId == activity.getIntent().getIntExtra("display", -1)
                && displays.getDisplay(displayId) != null;
    }

    void ready(XrViewerActivity activity) {
        if (viewer != activity || !accepts(activity)) return;
        opening = false; active = true; error = ""; report();
    }

    void closed(XrViewerActivity activity) {
        if (viewer != activity) return;
        tracking(false);
        viewer = null; opening = false; active = false;
        pausedByUser = true;
        speech.cancel(); report();
    }

    void errorMessage(String message) { error = message; report(); }

    void failed(String message) {
        error = message; pausedByUser = true;
        closeViewer(); report();
    }

    void event(String name, Map<String, Object> args) {
        if (session == null) return;
        args.put("session", session);
        channel.invokeMethod(name, args);
    }

    void tracking(boolean value) {
        if (value == tracking) return;
        tracking = value;
        if (value) {
            if (tracker == null) tracker = new Xreal3Dof(phone);
            tracker.start();
        } else if (tracker != null) tracker.stop();
    }

    void clearText() { event("clear", new HashMap<>()); }
    boolean enabled() { return preferences.getBoolean("enabled", true); }

    Map<String, Object> state() {
        Map<String, Object> values = new HashMap<>();
        values.put("enabled", enabled()); values.put("available", externalDisplay() != null);
        values.put("active", active); values.put("error", error);
        values.put("sensitivity", (double) preferences.getFloat("sensitivity", 3));
        if (viewer != null) viewer.describe(values);
        return values;
    }
    void reportTracking() {
        Map<String, Object> values = new HashMap<>();
        if (tracking && tracker != null) {
            Xreal3Dof.Snapshot pose = tracker.snapshot();
            values.put("trackingStatus", pose.status());
            values.put("trackingReady", pose.ready());
            values.put("calibrated", pose.calibrated() && pose.ready());
            values.put("calibrationProgress", pose.progressPercent());
        }
        if (glass != null) glass.update(values);
        event("tracking", values);
    }
    void report() {
        Map<String, Object> values = state();
        if (glass != null) glass.update(values);
        event("state", values);
    }

    private void closeViewer() {
        tracking(false);
        handler.removeCallbacks(openLater);
        pendingSpeechSession = null;
        speech.cancel();
        XrViewerActivity old = viewer;
        viewer = null; active = opening = false;
        if (old != null) old.finish();
    }

    public void resume() { foreground = true; schedule(); }
    public void pause() { foreground = false; closeViewer(); report(); }
    public void dispose() {
        closeViewer(); closeGlass(); session = null;
        if (tracker != null) { tracker.close(); tracker = null; }
        displays.unregisterDisplayListener(this);
        channel.setMethodCallHandler(null);
        if (current.get() == this) current.clear();
    }
    @Override public void onDisplayAdded(int id) { schedule(); report(); }
    @Override public void onDisplayChanged(int id) {
        if (viewer != null && id == displayId) { viewer.refresh(); report(); }
    }
    @Override public void onDisplayRemoved(int id) {
        if (displayId == id) { closeViewer(); displayId = -1; pausedByUser = false; }
        schedule(); report();
    }
}
