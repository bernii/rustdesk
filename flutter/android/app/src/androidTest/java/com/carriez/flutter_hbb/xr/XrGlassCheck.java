package com.carriez.flutter_hbb.xr;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import com.carriez.flutter_hbb.xr.glass.XrGlassHost;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the glass trackpad over the real FlutterView with injected touches and
 * screenshots: the frames, the gestures that reach the session, the settings panel
 * scrolling inside its glass, and the lock. No remote session or glasses are involved.
 */
final class XrGlassCheck {
    private final Instrumentation instrumentation;
    private final List<Map<String, Object>> actions = Collections.synchronizedList(new ArrayList<>());
    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    private final List<Float> distances = Collections.synchronizedList(new ArrayList<>());
    private Activity activity;
    private XrGlassHost host;
    private View root;

    XrGlassCheck(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    String run() throws Exception {
        activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                com.carriez.flutter_hbb.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> {
            host = new XrGlassHost(activity, new XrGlassHost.Events() {
                @Override public void action(Map<String, Object> args) {
                    actions.add(new HashMap<>(args));
                    // Dart's echo of what it was told.
                    Object name = args.get("action");
                    if ("anchored".equals(name)) echo("anchored", args.get("value"));
                }
                @Override public void failed(String message) { failures.add(message); }
                @Override public void screen(float distance, float curvature, boolean prediction) { distances.add(distance); }
            }, 3f);
            Map<String, Object> state = new HashMap<>();
            state.put("width", 3840); state.put("height", 2160);
            state.put("surfaceWidth", 1920); state.put("surfaceHeight", 1200);
            state.put("outputWidth", 1920); state.put("outputHeight", 1200);
            state.put("anchored", true);
            state.put("trackingStatus", "Tracking · calibrated");
            host.update(state);
            host.show(true);
            root = activity.getWindow().getDecorView();
        });
        try {
            return exercise();
        } finally {
            instrumentation.runOnMainSync(() -> { host.close(); activity.finish(); });
        }
    }

    private void echo(String key, Object value) {
        Map<String, Object> values = new HashMap<>();
        values.put(key, value);
        host.update(values);
    }

    private String exercise() throws Exception {
        int w = root.getWidth(), h = root.getHeight();
        // Once the window has drawn the glass's views, the cover hides Flutter's screen,
        // whatever that's showing, until the first frame.
        pause(300);
        Bitmap early = shot("glass-0-starting");
        check(!flutterHome(early, w), "the cover hides Flutter's screen until the first frame");
        // The cover is one flat color, so any spread means a frame has been drawn.
        waitFor(() -> {
            // Screenshots are rate-limited, and come back null if asked for too soon.
            pause(1000);
            Bitmap frame = instrumentation.getUiAutomation().takeScreenshot();
            return frame != null && spread(frame, w, h) > 40;
        }, 60000, "the first frame");
        check(failures.isEmpty(), "glass renderer: " + failures);
        pause(4000);
        Bitmap first = shot("glass-1-locked");
        check(!flutterHome(first, w), "the glass draws over the FlutterView");
        check(control("Text for the remote computer", false) == null, "it starts fully locked, with no text field");
        check(control("Settings", false) == null, "it starts fully locked, with no gear");

        // Locked, the pad still takes the session's input.
        drag(w / 2f, h / 2f, w / 2f + 200, h / 2f, 12, 240);
        pause(150);
        check(count("move") > 0, "one finger moves the pointer");
        actions.clear();
        tap(w / 2f, h * 0.6f);
        pause(700); // past the double-tap window, so the next tap can't dismiss the gear
        check(count("button") == 2, "a tap is a button pair: " + actions);
        actions.clear();

        for (int attempt = 0; attempt < 3 && count("infinity") == 0; attempt++) {
            infinity(w / 2f, h / 2f);
            pause(300);
            if (control("Settings", false) != null) break;
        }
        waitFor(() -> control("Settings", false) != null, 30000, "the infinity gesture to bring out the gear");
        check(control("Text for the remote computer", false) != null, "the field comes out with the gear");
        tap(control("Settings", false));
        // Views that are gone aren't found, so it can only be looked up once it's out.
        waitFor(() -> {
            View out = control("Settings", true);
            return out != null && out.getAlpha() > 0.99f;
        }, 30000, "the settings panel to open");
        ScrollView panel = (ScrollView) control("Settings", true);
        pause(500);
        check(panel.canScrollVertically(1), "the settings scroll inside the glass");
        shot("glass-2-settings-top");

        int[] at = new int[2];
        panel.getLocationOnScreen(at);
        float x = at[0] + panel.getWidth() / 2f, top = at[1] + panel.getHeight() * 0.2f, bottom = at[1] + panel.getHeight() * 0.8f;
        drag(x, bottom, x, top, 20, 300);
        pause(800);
        check(panel.getScrollY() > 0, "a vertical drag scrolls the panel");
        check(distances.isEmpty(), "scrolling over a slider doesn't change it: " + distances);
        shot("glass-3-settings-middle");
        for (int i = 0; i < 4; i++) { drag(x, bottom, x, top, 20, 200); pause(600); }
        check(!panel.canScrollVertically(1), "the panel reaches its end");
        shot("glass-4-settings-bottom");
        check(count("button") == 0, "touches on the panel stay off the pad: " + actions);

        // 3DoF off swaps the tracking section for the flat view.
        instrumentation.runOnMainSync(() -> panel.scrollTo(0, 0));
        pause(300);
        View tracking = control("Head tracking", false);
        check(tracking != null, "Head tracking switch");
        tap(tracking);
        pause(500);
        check(count("anchored") == 1, "the switch reaches the session: " + actions);
        check(control("Fit desktop", false).isShown(), "the flat view's settings show with tracking off");
        shot("glass-5-settings-flat");
        tap(tracking);
        pause(300);

        // A tap outside closes it, and only closes it.
        actions.clear();
        tap(w / 2f, at[1] / 2f);
        waitFor(() -> panel.getVisibility() != View.VISIBLE, 30000, "a tap outside to close the panel");
        check(count("button") == 0, "the closing tap doesn't click: " + actions);
        pause(500);

        // The lock: a short press stays locked, two seconds unlocks.
        View lock = control("Touch surface locked. Hold for 2 seconds to unlock.", false);
        check(lock != null, "the lock's locked label");
        tap(lock);
        pause(300);
        check(count("locked") == 0, "a short press stays locked");
        press(lock, 2300);
        pause(200);
        check(count("locked") == 1 && Boolean.FALSE.equals(first("locked").get("value")), "holding 2 s unlocks: " + actions);
        waitFor(() -> {
            View button = control("Pointer speed", false);
            return button != null && button.isShown();
        }, 10000, "unlocking to bring the top row out");
        pause(4000);
        shot("glass-6-unlocked");

        // Locking puts the gear away, and unlocking brings it back.
        actions.clear();
        tap(control("Lock touch surface", false));
        waitFor(() -> control("Settings", false) == null, 30000, "locking to put the gear away");
        check(count("locked") == 1 && Boolean.TRUE.equals(first("locked").get("value")), "a tap locks: " + actions);
        actions.clear();
        pause(1000);
        press(control("Touch surface locked. Hold for 2 seconds to unlock.", false), 2300);
        waitFor(() -> {
            View gear = control("Settings", false);
            return gear != null && gear.isShown();
        }, 30000, "unlocking to bring the gear out");
        pause(3000);
        shot("glass-7-unlocked-gear");
        check(failures.isEmpty(), "glass renderer: " + failures);

        // Untouched, it stops drawing; a touch brings it back.
        android.opengl.GLSurfaceView surface = surface();
        pause(10000);
        check(surface.getRenderMode() == android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY, "untouched, the glass stops drawing");
        tap(w / 2f, h / 2f);
        pause(300);
        check(surface.getRenderMode() == android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY, "a touch brings it back");
        return "glass trackpad over FlutterView: fully locked start, frames, gestures, infinity, scrolling settings, lock, idle freeze";
    }

    private android.opengl.GLSurfaceView surface() {
        final View[] found = new View[1];
        instrumentation.runOnMainSync(() -> {
            ArrayList<View> views = new ArrayList<>();
            root.findViewsWithText(views, "Trackpad surface", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            found[0] = views.isEmpty() ? null : views.get(0);
        });
        check(found[0] != null, "the glass surface");
        return (android.opengl.GLSurfaceView) found[0];
    }

    /** A sideways figure eight, twice, without lifting. */
    private void infinity(float cx, float cy) {
        float density = root.getResources().getDisplayMetrics().density;
        float width = 160 * density, height = 80 * density;
        int steps = 96;
        long t = SystemClock.uptimeMillis();
        event(t, t, MotionEvent.ACTION_DOWN, cx, cy);
        float x = cx, y = cy;
        for (int i = 1; i <= steps; i++) {
            double angle = 2 * 2 * Math.PI * i / steps;
            x = (float) (cx + width * Math.sin(angle) / 2);
            y = (float) (cy + height * Math.sin(2 * angle) / 2);
            pause(8);
            // Stamped, like a tap: the recognizer goes by event times, which load mustn't stretch.
            event(t, t + 8L * i, MotionEvent.ACTION_MOVE, x, y);
        }
        event(t, t + 8L * steps, MotionEvent.ACTION_UP, x, y);
    }

    private Map<String, Object> first(String name) {
        synchronized (actions) { for (Map<String, Object> a : actions) if (name.equals(a.get("action"))) return a; }
        throw new AssertionError("no " + name);
    }

    private int count(String name) {
        int n = 0;
        synchronized (actions) { for (Map<String, Object> a : actions) if (name.equals(a.get("action"))) n++; }
        return n;
    }

    private View control(String description, boolean scrolling) {
        final View[] found = new View[1];
        instrumentation.runOnMainSync(() -> {
            ArrayList<View> views = new ArrayList<>();
            root.findViewsWithText(views, description, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            // The search is by substring, and the surface's own description mentions settings.
            for (View v : views) {
                if ((v instanceof ScrollView) == scrolling && description.contentEquals(v.getContentDescription())) found[0] = v;
            }
        });
        return found[0];
    }

    /** RustDesk's home screen behind the glass: its blue app bar is the one saturated thing at the top. */
    private static boolean flutterHome(Bitmap image, int w) {
        int c = image.getPixel(w / 8, image.getHeight() / 16);
        int r = (c >> 16) & 255, b = c & 255;
        return b - r > 80;
    }

    /** Brightest minus darkest across a grid of samples; a blank surface or the cover has next to none. */
    private static int spread(Bitmap image, int w, int h) {
        int low = 255, high = 0;
        for (int y = h / 10; y < h * 9 / 10; y += h / 40) for (int x = w / 10; x < w * 9 / 10; x += w / 20) {
            int green = (image.getPixel(x, y) >> 8) & 255;
            low = Math.min(low, green);
            high = Math.max(high, green);
        }
        return high - low;
    }

    private interface Condition { boolean holds(); }

    private void waitFor(Condition condition, long ms, String what) {
        long until = SystemClock.uptimeMillis() + ms;
        while (true) {
            if (condition.holds()) return;
            check(failures.isEmpty(), "glass renderer: " + failures);
            check(SystemClock.uptimeMillis() < until, "timed out waiting for " + what);
            pause(100);
        }
    }

    private Bitmap shot(String name) throws Exception {
        pause(1000);
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        check(image != null, "screenshot");
        try (FileOutputStream out = new FileOutputStream(new File(instrumentation.getTargetContext().getFilesDir(), name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return image;
    }

    private static void pause(long ms) { SystemClock.sleep(ms); }

    private float[] center(View v) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return new float[]{at[0] + v.getWidth() / 2f, at[1] + v.getHeight() / 2f};
    }

    private void tap(View v) { float[] c = center(v); tap(c[0], c[1]); }

    private void tap(float x, float y) {
        long t = SystemClock.uptimeMillis();
        event(t, t, MotionEvent.ACTION_DOWN, x, y);
        pause(50);
        // Stamped, as a loaded emulator can take longer than a tap's 350 ms to deliver the down.
        event(t, t + 50, MotionEvent.ACTION_UP, x, y);
    }

    private void press(View v, long ms) {
        float[] c = center(v);
        long t = SystemClock.uptimeMillis();
        event(t, t, MotionEvent.ACTION_DOWN, c[0], c[1]);
        pause(ms);
        event(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, c[0], c[1]);
    }

    private void drag(float x0, float y0, float x1, float y1, int steps, long ms) {
        long t = SystemClock.uptimeMillis();
        event(t, t, MotionEvent.ACTION_DOWN, x0, y0);
        for (int i = 1; i <= steps; i++) {
            pause(ms / steps);
            event(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps);
        }
        event(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x1, y1);
    }

    private void event(long down, long time, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, time, action, x, y, 0);
        e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.sendPointerSync(e);
        e.recycle();
    }
}
