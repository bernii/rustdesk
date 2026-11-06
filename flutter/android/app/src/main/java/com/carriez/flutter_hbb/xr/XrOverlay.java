package com.carriez.flutter_hbb.xr;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

final class XrOverlay extends View {
    private final XrController owner;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private Bitmap cursor;
    private Object cursorBytes;
    private long holdStarted = -1, unlockedUntil;
    private boolean wasHolding, wasLocked = true, wasListening;
    private float micAlpha;
    private ValueAnimator micAnimation;
    private final RectF clear = new RectF();

    XrOverlay(Context context, XrController owner) {
        super(context); this.owner = owner;
        dp = getResources().getDisplayMetrics().density;
        setContentDescription("XR remote desktop status and Clear text");
    }
    private float number(String key, float fallback) {
        Object value = owner.overlay.get(key);
        return value instanceof Number ? ((Number) value).floatValue() : fallback;
    }
    private boolean flag(String key, boolean fallback) {
        Object value = owner.overlay.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
    void refresh() {
        Object bytes = owner.overlay.get("cursor");
        if (bytes != cursorBytes) {
            cursorBytes = bytes;
            Bitmap previous = cursor;
            cursor = bytes instanceof byte[] ? BitmapFactory.decodeByteArray((byte[]) bytes, 0, ((byte[]) bytes).length) : null;
            if (previous != null) previous.recycle();
        }
        boolean holding = flag("holding", false), locked = flag("locked", true);
        if (holding && !wasHolding) holdStarted = SystemClock.uptimeMillis();
        if (!holding) holdStarted = -1;
        if (wasLocked && !locked) unlockedUntil = SystemClock.uptimeMillis() + 600;
        wasHolding = holding; wasLocked = locked;
        boolean listening = flag("listening", false);
        if (listening != wasListening) {
            wasListening = listening;
            if (micAnimation != null) micAnimation.cancel();
            float target = listening ? 1 : 0;
            if (Build.VERSION.SDK_INT < 26 || !ValueAnimator.areAnimatorsEnabled()) micAlpha = target;
            else {
                micAnimation = ValueAnimator.ofFloat(micAlpha, target);
                micAnimation.setDuration(listening ? 280 : 240);
                micAnimation.addUpdateListener(animation -> { micAlpha = (float) animation.getAnimatedValue(); invalidate(); });
                micAnimation.start();
            }
        }
        invalidate();
    }
    @Override protected void onDetachedFromWindow() {
        if (micAnimation != null) micAnimation.cancel();
        super.onDetachedFromWindow();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = number("width", 0), h = number("height", 0);
        float viewportRight = getWidth(), viewportTop = 0;
        if (w > 0 && h > 0) {
            float scale = flag("fit", true) ? Math.min(getWidth() / w, getHeight() / h) : 1;
            float left = w * scale <= getWidth() ? (getWidth() - w * scale) / 2 : -(w * scale - getWidth()) * number("panX", .5f);
            float top = h * scale <= getHeight() ? (getHeight() - h * scale) / 2 : -(h * scale - getHeight()) * number("panY", .5f);
            viewportRight = left + w * scale; viewportTop = top;
            float x = left + number("x", 0) * scale, y = top + number("y", 0) * scale;
            if (flag("cursorEmbedded", false)) {
                // The received frame already contains the cursor.
            } else if (cursor != null) {
                float cw = number("cursorWidth", cursor.getWidth()) * scale;
                float ch = number("cursorHeight", cursor.getHeight()) * scale;
                x -= number("hotx", 0) * scale; y -= number("hoty", 0) * scale;
                canvas.drawBitmap(cursor, null, new RectF(x, y, x + cw, y + ch), paint);
            } else {
                Path arrow = new Path(); arrow.moveTo(x, y); arrow.lineTo(x + 9 * dp, y + 22 * dp);
                arrow.lineTo(x + 12 * dp, y + 12 * dp); arrow.lineTo(x + 22 * dp, y + 9 * dp); arrow.close();
                paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL); canvas.drawPath(arrow, paint);
                paint.setColor(Color.BLACK); paint.setStrokeWidth(dp); paint.setStyle(Paint.Style.STROKE); canvas.drawPath(arrow, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        float clearRight = Math.min(getWidth() - 64 * dp, viewportRight - 8 * dp);
        float clearTop = Math.max(16 * dp, viewportTop + 16 * dp);
        clear.set(clearRight - 116 * dp, clearTop, clearRight, clearTop + 40 * dp);
        paint.setColor(0xdd192523); canvas.drawRoundRect(clear, 12 * dp, 12 * dp, paint);
        text(canvas, "Clear text", clear.centerX(), clear.top + 26 * dp, 14 * dp);
        lock(canvas, getWidth() - 32 * dp, 36 * dp, 20 * dp, -1);
        String draft = String.valueOf(owner.overlay.getOrDefault("transcript", ""));
        if (!draft.isEmpty()) {
            // Wrap rather than dropping the ends of long hypotheses. Keep the latest six lines.
            paint.setTextSize(20 * dp);
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            for (String paragraph : draft.split("\n", -1)) {
                if (paragraph.isEmpty()) lines.add("");
                while (!paragraph.isEmpty()) {
                    int count = paint.breakText(paragraph, true, getWidth() - 100 * dp, null);
                    if (count == 0) break;
                    lines.add(paragraph.substring(0, count)); paragraph = paragraph.substring(count);
                }
            }
            int start = Math.max(0, lines.size() - 6);
            float y = getHeight() - (lines.size() - start) * 27 * dp - 20 * dp;
            paint.setColor(0xdd101918);
            canvas.drawRoundRect(32 * dp, y - 27 * dp, getWidth() - 32 * dp, getHeight() - 14 * dp, 12 * dp, 12 * dp, paint);
            for (int i = start; i < lines.size(); i++, y += 27 * dp) text(canvas, lines.get(i), getWidth() / 2f, y, 20 * dp);
        }
        long now = SystemClock.uptimeMillis();
        if (holdStarted >= 0 || now < unlockedUntil) {
            float progress = holdStarted >= 0 ? Math.min(1, (now - holdStarted) / 2000f) : 1;
            lock(canvas, getWidth() / 2f, getHeight() / 2f, 64 * dp, progress);
            text(canvas, holdStarted >= 0 ? "Hold to unlock" : "Unlocked", getWidth() / 2f, getHeight() / 2f + 92 * dp, 18 * dp);
            postInvalidateOnAnimation();
        } else if (micAlpha > 0) {
            float x = getWidth() / 2f, y = getHeight() / 2f;
            canvas.saveLayerAlpha(x - 90 * dp, y - 90 * dp, x + 90 * dp, y + 110 * dp, (int) (micAlpha * 255));
            canvas.scale(.86f + .14f * micAlpha, .86f + .14f * micAlpha, x, y);
            paint.setColor(0xdd192523); canvas.drawCircle(x, y, 62 * dp, paint);
            paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3 * dp);
            canvas.drawRoundRect(x - 9 * dp, y - 30 * dp, x + 9 * dp, y + 2 * dp, 9 * dp, 9 * dp, paint);
            canvas.drawArc(x - 17 * dp, y - 18 * dp, x + 17 * dp, y + 10 * dp, 0, 180, false, paint);
            canvas.drawLine(x, y + 10 * dp, x, y + 18 * dp, paint);
            text(canvas, "Listening", x, y + 42 * dp, 16 * dp);
            canvas.restore();
        }
    }
    private void text(Canvas canvas, String value, float x, float y, float size) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); paint.setTextSize(size); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(value, x, y, paint);
    }
    private void lock(Canvas canvas, float x, float y, float radius, float progress) {
        canvas.save(); canvas.translate(x - radius, y - radius); canvas.scale(radius / 22, radius / 22);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); canvas.drawCircle(22, 22, 20, paint);
        if (progress >= 0) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(0xffd9561a);
            canvas.drawArc(2, 2, 42, 42, -90, progress * 360, false, paint);
        }
        canvas.translate(12.5f, 12.5f); canvas.scale(19f / 24, 19f / 24);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); paint.setColor(0xff4a1b05);
        canvas.drawRoundRect(5, 11, 19, 21, 2.5f, 2.5f, paint);
        Path p = new Path(); p.moveTo(8, 11); p.lineTo(8, 8);
        if (flag("locked", true)) { p.cubicTo(8, 2.67f, 16, 2.67f, 16, 8); p.lineTo(16, 11); }
        else p.cubicTo(8, 3.1f, 14.2f, 2.3f, 15.7f, 6.5f);
        canvas.drawPath(p, paint); canvas.restore(); paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && clear.contains(event.getX(), event.getY())) { owner.clearText(); performClick(); }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
