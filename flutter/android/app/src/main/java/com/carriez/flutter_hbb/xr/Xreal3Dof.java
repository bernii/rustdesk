/*
 * Standalone export of the local Android XREAL 1S tracker and projection implementation.
 * Protocol references: one-xr c7b000444cacc23090a71867e46cebc549205dd5; xreal-linux findings.
 * No vendor SDK/firmware/third-party runtime is bundled. Retained reference notice:
 *
 * Copyright (c) 2026 Neil Skaria
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.carriez.flutter_hbb.xr;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

// Source fingerprints (regenerate rather than hand-edit bundled internals):
// ImuMath:9b9d01251534
// GlassesImuParser:a38acd0bcbc3
// SampleGate:c9143c57217f
// SampleClock:7f94acb1fae3
// ImuStream:6a92f614c726
// AdaptiveHeadPose:9351e109fbf8
// GravityCalibration:0dd612a87843
// TrackingRefinement:d23754b5dc4c
// GravityTiltCorrection:95bc8dd56139
// TemperatureFilter:4419a92e129d
// BiasProfile:a0c667c39df6
// FactoryBiasTable:eca330b915e0
// FactoryConfigReply:5631560cc1b3
// CalibrationStore:e1b211c2917c
// FactoryCalibrationSession:0e5be1c2aeac
// GlassesTracker:eba5b4435a90
// MotionPrediction:2397c1d2a4ec
// AnchorPredictionFilter:190ff69edfca
// QuietAnchorPredictionFilter:5efa1897329e
// PredictionModel:d046bdb14532
// AnchoredScreen:4367ec56af88
// CalibrationHud:76f28b467818
// PlaneShader:5462b79499ba

/**
 * Copy this one file into an Android app; change the package declaration if desired.
 * Requires minSdk 26, compileSdk 33+, Java 17, INTERNET and ACCESS_NETWORK_STATE.
 * No probe resources, Gradle module, XREAL SDK, native library or third-party runtime.
 *
 * Host owns the external display/window, Surface, vsync loop and source bitmap.
 * Lifecycle/control methods: one owner (normally UI thread). snapshot(): any thread.
 * Renderer: create/use/close on one render thread, with exclusive source-bitmap access.
 * Prediction is intentionally OFF in the public rendering API.
 *
 * Generated from tested probe sources. Regenerate with tools/portable/export.py;
 * do not hand-edit the bundled internals in this workspace. A copied file is standalone.
 */
@android.annotation.TargetApi(26)
public final class Xreal3Dof implements AutoCloseable {
    private final GlassesTracker tracker;
    private boolean closed;

    public Xreal3Dof(Context context) {
        if(context==null) throw new IllegalArgumentException("Context is required");
        tracker=new GlassesTracker(context.getApplicationContext());
    }
    private void checkOpen() { if(closed) throw new IllegalStateException("Tracker is closed"); }
    /** Starts receive-only IMU plus the bounded read-only factory configuration getter. */
    public void start() { checkOpen(); tracker.start(); }
    /** Stop invalidates the published pose immediately; start opens a new stream. */
    public void stop() { if(!closed) tracker.stop(); }
    /** Pause/resume preserve the user's start/stop intent, not the old transport epoch. */
    public void pause() { if(!closed) tracker.pause(); }
    public void resume() { checkOpen(); tracker.resume(); }
    /** Change the screen's reference direction without discarding calibration. */
    public void recenter() { checkOpen(); tracker.recenter(); }
    /** Keep useful pose/bias while checking independent intervals. Starts tracking. */
    public void recheckCalibration() { checkOpen(); tracker.recalibrate(); tracker.start(); }
    /** Explicit stationary recovery: place glasses on a stable surface first. Starts tracking. */
    public void calibrateStationary() { checkOpen(); tracker.calibrateDesk(); tracker.start(); }
    /** Delete this physical device/sensor's learned profile when its stream is available. */
    public void forgetCalibration() { checkOpen(); tracker.forgetCalibration(); tracker.start(); }
    public Snapshot snapshot() { return new Snapshot(tracker.reading()); }
    @Override public void close() { if(!closed) { tracker.stop(); closed=true; } }

    /** Immutable published reading. Arrays are returned as copies; freshness is evaluated now. */
    public static final class Snapshot {
        private final GlassesTracker.Reading reading;
        private Snapshot(GlassesTracker.Reading reading) { this.reading=reading; }
        public String status() { return reading.status; }
        public String calibrationMethod() { return reading.calibrationMethod; }
        public String factoryStatus() { return reading.factoryStatus; }
        public boolean fresh() { return reading.fresh(); }
        public boolean ready() { return reading.ready(); }
        public boolean biasReady() { return reading.biasReady; }
        public boolean calibrated() { return reading.calibrated; }
        public boolean waitingForConfidence() { return reading.calibrationWaiting; }
        public int progressPercent() { return reading.progress; }
        public int stationaryCalibrationRevision() { return reading.stationaryCalibrationRevision; }
        public int calibrationRejections() { return reading.calibrationRejections; }
        public int temperatureRecords() { return reading.temperatureRecords; }
        public Double temperatureCelsius() { return reading.temperature; }
        public Double savedConfirmationSeconds() { return reading.startupConfirmationSeconds; }
        public double uncertaintyRadPerSecond() { return reading.calibrationBiasSpread; }
        public double acceptedHz() { return reading.hz; }
        public long acceptedSamples() { return reading.samples; }
        public long timestampGaps() { return reading.gaps; }
        public long duplicateSamples() { return reading.duplicates; }
        public long clockResets() { return reading.clockResets; }
        public long invalidReports() { return reading.invalidReports; }
        public double receiveMonotonicSeconds() { return reading.receiptSeconds; }
        public double sampleMonotonicSeconds() { return reading.sampleSeconds; }
        public double excessDeliveryDelayMs() { return reading.excessDelayMs; }
        /** Hamilton WXYZ, body/camera to reference world; no render prediction. */
        public double[] quaternionWxyz() { return reading.quaternion.clone(); }
        public double[] biasRadPerSecond() { return reading.bias.clone(); }
        public double[] angularVelocityRadPerSecond() { return reading.angularVelocity.clone(); }
    }

    /** Pure geometry for alternate renderers, pointer mapping and independent shader checks. */
    public static final class Projection {
        private final AnchoredScreen plane;
        public Projection(int outputWidth,int outputHeight,int sourceWidth,int sourceHeight,
                double horizontalFovDegrees,double distance,double[] quaternionWxyz) {
            validateProjection(outputWidth,outputHeight,sourceWidth,sourceHeight,horizontalFovDegrees,distance,quaternionWxyz);
            plane=new AnchoredScreen(outputWidth,outputHeight,sourceWidth,sourceHeight,horizontalFovDegrees,distance,quaternionWxyz);
        }
        public boolean visible() { return plane.visible(); }
        /** Row-major Android perspective Matrix: source pixel coordinates -> output pixels. */
        public float[] homography() { return plane.matrix.clone(); }
        public float[] clipPolygon() { return plane.clipPolygon.clone(); }
        /** Bounding box, not the exact visible source polygon. */
        public float[] sourceBounds() { return new float[]{plane.sourceLeft,plane.sourceTop,plane.sourceRight,plane.sourceBottom}; }
        /** Null for a point behind the near plane. Output can lie outside the surface. */
        public float[] project(float sourceX,float sourceY) { return plane.project(sourceX,sourceY); }
    }
    private static void validateProjection(int w,int h,int sw,int sh,double fov,double distance,double[] q) {
        if(w<=0 || h<=0 || sw<=0 || sh<=0 || !Double.isFinite(fov) || fov<20 || fov>100
                || !Double.isFinite(distance) || distance<0.25 || distance>4 || q==null || q.length!=4)
            throw new IllegalArgumentException("Invalid projection dimensions, FOV, distance or quaternion");
        double n=0; for(double x:q) { if(!Double.isFinite(x)) throw new IllegalArgumentException("Nonfinite quaternion"); n+=x*x; }
        if(!Double.isFinite(n) || n<1e-18) throw new IllegalArgumentException("Invalid quaternion length");
    }

    /**
     * Draw a fixed source plane to a native-sized Canvas. API 33+ hardware Canvas uses
     * the one-pass AGSL shader; older/software canvases use clipped homography drawing.
     * This is a bitmap renderer, not a video decoder or external-OES-texture importer.
     * Reuse this object and the source bitmap. Caller retains bitmap ownership.
     */
    public static final class Renderer implements AutoCloseable {
        private Bitmap source;
        private PlaneShader shader;
        private boolean shaderFailed,closed;
        private String backend="Not drawn",fallbackReason="";
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Matrix matrix=new android.graphics.Matrix();
        private final android.graphics.Path clip=new android.graphics.Path();
        private final android.graphics.Rect destination=new android.graphics.Rect();
        private final CalibrationHud hud=new CalibrationHud();
        private double[] displayedPose=identity();

        public Renderer(Bitmap source) { setSource(source); }
        /** Call only on the rendering thread; never recycle/mutate a bitmap during a draw. */
        public void setSource(Bitmap source) {
            if(closed) throw new IllegalStateException("Renderer is closed");
            if(source==null || source.isRecycled()) throw new IllegalArgumentException("Live source bitmap required");
            if(this.source==source) return;
            this.source=source; shader=null; shaderFailed=false; fallbackReason="";
        }
        public String backend() { return backend; }
        public String fallbackReason() { return fallbackReason; }
        public double[] displayedQuaternionWxyz() { return displayedPose.clone(); }
        /** Freeze pose updates while continuing to render source content; stale data also holds last pose. */
        public void draw(Canvas canvas,Snapshot snapshot,double horizontalFovDegrees,double distance,
                boolean frozen,boolean showCalibrationHud) {
            if(closed) throw new IllegalStateException("Renderer is closed");
            if(canvas==null || source.isRecycled()) throw new IllegalArgumentException("Canvas/live source required");
            boolean fresh=snapshot!=null && snapshot.ready();
            if(!frozen && fresh) displayedPose=snapshot.quaternionWxyz();
            validateProjection(canvas.getWidth(),canvas.getHeight(),source.getWidth(),source.getHeight(),horizontalFovDegrees,distance,displayedPose);
            CalibrationHud.State state=showCalibrationHud
                    ? CalibrationHud.State.forTracking(true,frozen,fresh,snapshot!=null && snapshot.biasReady(),
                        snapshot!=null && snapshot.calibrated(),snapshot==null?0:snapshot.progressPercent(),
                        snapshot!=null && snapshot.waitingForConfidence(),System.nanoTime())
                    : CalibrationHud.State.HIDDEN;
            canvas.drawColor(Color.BLACK);
            if(android.os.Build.VERSION.SDK_INT>=33 && canvas.isHardwareAccelerated() && !shaderFailed) {
                try {
                    if(shader==null) shader=new PlaneShader(source);
                    shader.calibration(state);
                    shader.draw(canvas,canvas.getWidth(),canvas.getHeight(),horizontalFovDegrees,distance,
                            displayedPose,MotionPrediction.NONE,0,0);
                    backend="AGSL single-pass"; return;
                } catch(RuntimeException failure) {
                    shader=null; shaderFailed=true; fallbackReason=failure.getClass().getSimpleName()+": "+failure.getMessage();
                }
            }
            backend="Canvas homography";
            AnchoredScreen p=new AnchoredScreen(canvas.getWidth(),canvas.getHeight(),source.getWidth(),source.getHeight(),
                    horizontalFovDegrees,distance,displayedPose);
            if(!p.visible()) return;
            clip.reset(); clip.moveTo(p.clipPolygon[0],p.clipPolygon[1]);
            for(int i=2;i<p.clipPolygon.length;i+=2) clip.lineTo(p.clipPolygon[i],p.clipPolygon[i+1]);
            clip.close(); matrix.setValues(p.matrix);
            int saved=canvas.save();
            try {
                canvas.clipPath(clip); canvas.concat(matrix);
                destination.set(0,0,source.getWidth(),source.getHeight());
                // Explicit destination prevents Android density scaling from breaking 1:1.
                canvas.drawBitmap(source,null,destination,paint);
                hud.draw(canvas,state,source.getWidth(),source.getHeight());
            } finally { canvas.restoreToCount(saved); }
        }
        public void draw(Canvas canvas,Snapshot snapshot,double horizontalFovDegrees,double distance) {
            draw(canvas,snapshot,horizontalFovDegrees,distance,false,true);
        }
        @Override public void close() { closed=true; shader=null; source=null; hud.release(); }
    }

    // ---- Extracted from ImuMath.java ----
    static double[] v(double x,double y,double z) { return new double[]{x,y,z}; }
        static double[] add(double[] a,double[] b) { return v(a[0]+b[0],a[1]+b[1],a[2]+b[2]); }
        static double[] sub(double[] a,double[] b) { return v(a[0]-b[0],a[1]-b[1],a[2]-b[2]); }
        static double[] mul(double[] a,double b) { return v(a[0]*b,a[1]*b,a[2]*b); }
        static double dot(double[] a,double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
        static double norm(double[] a) { return Math.sqrt(dot(a,a)); }
        static double[] unit(double[] a) { return mul(a,1/Math.max(1e-12,norm(a))); }
        static double[] cross(double[] a,double[] b) { return v(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]); }
        static double[] limit(double[] a,double bound) { return mul(a,Math.min(1,Math.max(0,bound)/Math.max(1e-12,norm(a)))); }
        static boolean finite(double[] a) { for(double x:a) if(!Double.isFinite(x)) return false; return true; }
        static double[] identity() { return new double[]{1,0,0,0}; }
        static double[] inverse(double[] q) { return new double[]{q[0],-q[1],-q[2],-q[3]}; }
        static double[] product(double[] a,double[] b) {
            return new double[]{a[0]*b[0]-a[1]*b[1]-a[2]*b[2]-a[3]*b[3],
                a[0]*b[1]+a[1]*b[0]+a[2]*b[3]-a[3]*b[2],a[0]*b[2]-a[1]*b[3]+a[2]*b[0]+a[3]*b[1],
                a[0]*b[3]+a[1]*b[2]-a[2]*b[1]+a[3]*b[0]};
        }
        static double[] integrate(double[] q,double[] rate,double dt) {
            double speed=norm(rate); if(speed<1e-12 || dt<=0) return q;
            double half=speed*dt/2, f=Math.sin(half)/speed;
            double[] out=product(q,new double[]{Math.cos(half),rate[0]*f,rate[1]*f,rate[2]*f});
            double n=0; for(double x:out) n+=x*x; n=Math.sqrt(n); for(int i=0;i<4;i++) out[i]/=n; return out;
        }
        static double[] rotate(double[] q,double[] a) {
            double[] t=mul(cross(v(q[1],q[2],q[3]),a),2);
            return add(a,add(mul(t,q[0]),cross(v(q[1],q[2],q[3]),t)));
        }
        static final class Moments {
            int count; final double[] mean=new double[3], m2=new double[3];
            void add(double[] v) { count++; for(int i=0;i<3;i++) { double d=v[i]-mean[i]; mean[i]+=d/count; m2[i]+=d*(v[i]-mean[i]); } }
            double rms() { return count>1?Math.sqrt(Math.max(0,(m2[0]+m2[1]+m2[2])/(count-1))):0; }
        }

    // ---- Extracted from GlassesImuParser.java ----
    /** XREAL One-series 128-byte report body + 6-byte header. No control messages. */
    static final class GlassesImuParser {
        interface Listener { void sample(Sample sample); }
        static final class Sample {
            final long timeNanos;
            final float gx, gy, gz, ax, ay, az;
            final int command, sensorId;
            final long sensorTime, frame;
            final Double temperature;
            double[] gyro() { return new double[]{gx,gy,gz}; }
            double[] accel() { return new double[]{ax,ay,az}; }
            Sample(long timeNanos, float gx, float gy, float gz, float ax, float ay, float az) {
                this(timeNanos,gx,gy,gz,ax,ay,az,0x28,0,0,-1,null);
            }
            Sample(long timeNanos, float gx, float gy, float gz, float ax, float ay, float az,
                    int command,int sensorId,long sensorTime,long frame,Double temperature) {
                this.command=command; this.sensorId=sensorId; this.sensorTime=sensorTime; this.frame=frame; this.temperature=temperature;
                this.timeNanos = timeNanos;
                this.gx = gx; this.gy = gy; this.gz = gz;
                this.ax = ax; this.ay = ay; this.az = az;
            }
        }
        private final byte[] pending = new byte[134];
        private int size;
        long bytes, samples, otherReports, invalidReports, discardedBytes;

        void append(byte[] chunk, int count, Listener listener) {
            for (int i = 0; i < count; i++) {
                bytes++;
                pending[size++] = chunk[i];
                while (size >= 6 && !header()) discard();
                if (size != pending.length) continue;
                ByteBuffer b = ByteBuffer.wrap(pending).order(ByteOrder.LITTLE_ENDIAN);
                int type = b.getInt(30);
                if (type == 11) {
                    long time = b.getLong(14);
                    float gx = b.getFloat(34), gy = b.getFloat(38), gz = b.getFloat(42);
                    float ax = b.getFloat(46), ay = b.getFloat(50), az = b.getFloat(54);
                    if (time > 0 && finite(gx, gy, gz, ax, ay, az)
                            && Math.max(Math.abs(gx), Math.max(Math.abs(gy), Math.abs(gz))) < 40
                            && Math.max(Math.abs(ax), Math.max(Math.abs(ay), Math.abs(az))) < 200) {
                        samples++;
                        boolean normal=pending[0]==0x28;
                        float t=b.getFloat(70);
                        listener.sample(new Sample(time, gx, gy, gz, ax, ay, az,pending[0]&255,
                                normal?pending[74]&255:0,b.getLong(22),normal?Integer.toUnsignedLong(b.getInt(75)):-1,
                                normal && Float.isFinite(t) && t>=5 && t<=85?(double)t:null));
                    } else invalidReports++;
                } else otherReports++;
                size = 0;
            }
        }
        private boolean header() {
            return (pending[0] == 0x28 || pending[0] == 0x27) && pending[1] == 0x36
                    && pending[2] == 0 && pending[3] == 0 && pending[4] == 0 && pending[5] == (byte) 0x80;
        }
        private void discard() {
            System.arraycopy(pending, 1, pending, 0, --size);
            discardedBytes++;
        }
        private static boolean finite(float... values) {
            for (float value : values) if (!Float.isFinite(value)) return false;
            return true;
        }
    }

    // ---- Extracted from SampleGate.java ----
    /** One command/sensor per connection. Rejected packets never refresh motion freshness. */
    static final class SampleGate {
        private GlassesImuParser.Sample last, rollback;
        private int rollbackCount;
        int command=-1, sensor=-1;
        long accepted, duplicates, backwards, gaps, resets, otherSensors, counterRepeats, counterJumps, counterWraps;
        // -1 reject, 0 continuous, 1 new epoch (preserve pose, clear time-dependent windows).
        int accept(GlassesImuParser.Sample s) {
            if(command<0) { command=s.command; sensor=s.sensorId; }
            if(command!=s.command || sensor!=s.sensorId) { otherSensors++; return -1; }
            boolean broken=false;
            if(last!=null) {
                if(s.timeNanos==last.timeNanos) { duplicates++; return -1; }
                if(s.timeNanos<last.timeNanos) {
                    backwards++;
                    if(last.timeNanos-s.timeNanos>100_000_000 && s.frame>=0 && last.frame>=0 && s.frame<last.frame) {
                        if(rollback!=null && s.timeNanos>rollback.timeNanos && s.timeNanos-rollback.timeNanos<100_000_000
                                && ((s.frame-rollback.frame)&0xffffffffL)==1) rollbackCount++; else rollbackCount=1;
                        rollback=s;
                        if(rollbackCount<3) return -1;
                        resets++; broken=true;
                    } else { rollback=null; rollbackCount=0; return -1; }
                } else {
                    if(s.timeNanos-last.timeNanos>100_000_000) { gaps++; broken=true; }
                    if(s.frame>=0 && last.frame>=0) {
                        long delta=(s.frame-last.frame)&0xffffffffL;
                        if(delta==0) counterRepeats++; else if(delta!=1) counterJumps++;
                        if(s.frame<last.frame && delta<0x80000000L) counterWraps++;
                    }
                }
            }
            rollback=null; rollbackCount=0; last=s; accepted++; return broken?1:0;
        }
    }

    // ---- Extracted from SampleClock.java ----
    /** Lower-envelope clock mapping. Unknown minimum transit delay remains in the offset. */
    static final class SampleClock {
        private record Point(long second,double x,double y) {}
        private final ArrayList<Point> points=new ArrayList<>();
        private long origin,lastDevice;
        private double hostOrigin,lastArrival,lastFit;
        private int arrivals;
        double rate=1, sampleHostTime=Double.NaN, excessDelayMs;
        void reset() { points.clear(); origin=lastDevice=0; hostOrigin=lastArrival=lastFit=0; arrivals=0; rate=1; sampleHostTime=Double.NaN; excessDelayMs=0; }
        void accept(long device,double arrival) {
            if(device<=0 || !Double.isFinite(arrival) || arrival<=0 || (origin!=0 && device<=lastDevice)) return;
            if(origin!=0 && (device-lastDevice>100_000_000 || arrival<lastArrival)) reset();
            if(origin==0) { origin=device; hostOrigin=arrival; lastFit=arrival; }
            if(arrival!=lastArrival) arrivals++;
            lastDevice=device; lastArrival=arrival;
            double x=(device-origin)*1e-9,y=arrival-hostOrigin;
            Point p=new Point((long)Math.floor(x),x,y);
            if(!points.isEmpty() && points.get(points.size()-1).second==p.second) {
                Point old=points.get(points.size()-1);
                if(p.y-p.x<old.y-old.x) points.set(points.size()-1,p);
            } else { points.add(p); if(points.size()>32) points.remove(0); }
            if(arrival-lastFit>=0.5) {
                double elapsed=arrival-lastFit; lastFit=arrival;
                if(points.get(points.size()-1).x-points.get(0).x>=8) {
                    ArrayList<Double> slopes=new ArrayList<>();
                    for(int i=0;i<points.size();i++) for(int j=i+1;j<points.size();j++) {
                        Point a=points.get(i),b=points.get(j);
                        if(b.x-a.x>=4) slopes.add((b.y-a.y)/(b.x-a.x));
                    }
                    Collections.sort(slopes);
                    if(!slopes.isEmpty()) { double target=slopes.get(slopes.size()/2);
                        if(Math.abs(target-1)<=0.0005) rate+=Math.max(-0.00005*elapsed,Math.min(0.00005*elapsed,target-rate)); }
                }
            }
            if(x<1 || arrivals<20 || points.size()<2) { sampleHostTime=Double.NaN; return; }
            double offset=Double.POSITIVE_INFINITY;
            for(Point a:points) offset=Math.min(offset,a.y-rate*a.x);
            sampleHostTime=Math.min(arrival,hostOrigin+offset+rate*x);
            excessDelayMs=Math.max(0,(arrival-sampleHostTime)*1000);
        }
        static boolean fresh(double receipt,double sample,double now) {
            return receipt>0 && now>=receipt && now-receipt<0.1 && sample>0 && now>=sample && now-sample<0.1;
        }
    }

    // ---- Extracted from ImuStream.java ----
    /** Platform-independent pipeline shared by live networking and chunk replay tests. */
    static final class ImuStream {
        final GlassesImuParser parser=new GlassesImuParser();
        final SampleGate gate=new SampleGate();
        final SampleClock clock=new SampleClock();
        final AdaptiveHeadPose pose=new AdaptiveHeadPose();
        double lastAcceptedArrival;
        void append(byte[] data,int count,double arrival) {
            if(!Double.isFinite(arrival) || arrival<=0) return;
            parser.append(data,count,s->{
                int result=gate.accept(s); if(result<0) return;
                if(result==1) { pose.discontinuity(); clock.reset(); }
                if(s.command==0x28) clock.accept(s.timeNanos,arrival);
                pose.accept(s); lastAcceptedArrival=arrival;
            });
        }
        double predictionOrigin() { return Double.isFinite(clock.sampleHostTime)?clock.sampleHostTime:lastAcceptedArrival; }
    }

    // ---- Extracted from AdaptiveHeadPose.java ----
    /** Mac XR adaptive estimator port. Pose readiness and calibration confidence are independent. */
    static final class AdaptiveHeadPose {
        private final AnchorPredictionFilter predictor=new AnchorPredictionFilter();
        private final QuietAnchorPredictionFilter quietPredictor=new QuietAnchorPredictionFilter();
        BiasProfile profile=new BiasProfile();
        FactoryBiasTable factory;
        int revision,stationaryRevision,tiltRefinementCount,rejections;
        boolean ready,biasReady,validating=true,gravityCorrectionActive;
        double gravityErrorDegrees;
        Double startupConfirmationSeconds;
        String status="Waiting for glasses IMU";
        GravityCalibration calibration=new GravityCalibration();
        final TemperatureFilter temperature=new TemperatureFilter();
        private record Entry(GlassesImuParser.Sample sample,Double temperature) {}
        private record Proposal(BiasProfile.Observation observation,long start,long end,boolean stationaryQuality) {}
        private final ArrayList<Entry> recent=new ArrayList<>();
        private Proposal candidate;
        private BiasProfile.Observation provisional,tiltProposal;
        private double[] q=identity(),zero=identity(),gravity,total=new double[3],base=new double[3],residual=new double[3],velocity=new double[3];
        private double[] bootstrap,tiltAccumulated=new double[3];
        private long last,nextAttempt,streamStarted,validationStarted;
        private boolean extendedValidation;
        private boolean deskOnly,recheckingTrusted,startupCheckAllowed;
        final GravityTiltCorrection gravityCorrection=new GravityTiltCorrection();
        private final TrackingRefinement.TiltRefiner refiner=new TrackingRefinement.TiltRefiner();
        void recenter() { zero=inverse(q); }
        void recalibrate(boolean desk) {
            recheckingTrusted=!desk && ready && profile.latest()!=null && (revision>0 || startupConfirmationSeconds!=null) && (!validating || recheckingTrusted);
            startupCheckAllowed=!desk && profile.latest()!=null; validationStarted=0; extendedValidation=false; refiner.reset(); deskOnly=desk; validating=true; candidate=null; restartWindow();
            status=ready?"Tracking active; checking calibration in background":"Collecting calibration";
        }
        void forget() {
            profile=new BiasProfile(); revision++; ready=biasReady=false; provisional=null; bootstrap=null; tiltProposal=null;
            startupConfirmationSeconds=null; tiltAccumulated=new double[3]; tiltRefinementCount=0;
            gravityCorrection.forget(); gravity=null; total=new double[3]; base=new double[3]; residual=new double[3];
            discontinuity(); recalibrate(false);
        }
        void discontinuity() {
            recheckingTrusted=startupCheckAllowed=false; startupConfirmationSeconds=null; streamStarted=last=validationStarted=0; extendedValidation=false;
            predictor.reset(); quietPredictor.reset();
            velocity=new double[3]; temperature.reset(); gravityCorrectionActive=false; gravityErrorDegrees=0; gravityCorrection.reset(); refiner.reset();
            validating=true; candidate=null; restartWindow();
        }
        private void restartWindow() { calibration=new GravityCalibration(); recent.clear(); nextAttempt=0; tiltProposal=null; }
        double[] modelBias(Double t) { double[] b=factory==null?null:factory.anchoredBias(profile,t); return b!=null?b:profile.bias(t); }
        void installFactory(FactoryBiasTable table,BiasProfile saved) {
            factory=table;
            if(profile.latest()==null && saved.valid() && saved.latest()!=null) {
                profile=saved;
                if(ready) {
                    double[] b=modelBias(temperature.value); residual=add(residual,sub(base,b)); base=b; bootstrap=b.clone(); biasReady=true;
                    status="Device calibration loaded; validating in background";
                }
            }
            startupCheckAllowed=profile.latest()!=null;
        }
        void accept(GlassesImuParser.Sample s) {
            if(last!=0 && s.timeNanos<=last) return;
            double dt=last==0?0:(s.timeNanos-last)*1e-9;
            if(dt>0.1) { discontinuity(); dt=0; }
            last=s.timeNanos; temperature.accept(s.temperature,dt); if(streamStarted==0) streamStarted=s.timeNanos;
            if(validationStarted==0) validationStarted=s.timeNanos;
            double[] a=s.accel(); double magnitude=norm(a);
            if(!ready && profile.latest()!=null && magnitude>=7.5 && magnitude<=12) {
                total=profile.latest().bias().clone(); base=total.clone(); gravity=rotate(q,mul(unit(a),9.81)); ready=biasReady=true;
                status="Saved calibration; validating";
            }
            if(!ready && !deskOnly && magnitude>=7.5 && magnitude<=12) {
                gravity=rotate(q,a); ready=true; status="Tracking active; initial bias unknown";
            }
            if(ready) {
                double[] target=modelBias(temperature.value);
                if(target!=null) base=add(base,limit(sub(target,base),0.0002*dt));
                total=add(base,residual);
                if(bootstrap!=null) {
                    total=add(total,limit(sub(bootstrap,total),0.01*dt)); residual=sub(total,base);
                    if(norm(sub(bootstrap,total))<1e-10) bootstrap=null;
                }
                velocity=sub(s.gyro(),total);
                predictor.accept(s.timeNanos,s.frame,velocity,total);
                quietPredictor.accept(s.timeNanos,s.frame,velocity,total);
                q=integrate(q,velocity,dt);
                if(gravity!=null) {
                    double[] correction=gravityCorrection.accept(s,q,gravity,velocity,dt);
                    // Convert the world-frame correction to body coordinates for right multiplication.
                    q=integrate(q,rotate(inverse(q),correction),dt);
                    gravityCorrectionActive=gravityCorrection.active;
                    gravityErrorDegrees=gravityCorrection.errorDegrees;
                }
            }
            if(ready && biasReady && bootstrap==null && !deskOnly) {
                double[] step=refiner.accept(s,total);
                if(step!=null && norm(add(tiltAccumulated,step))<=0.0015 && norm(add(residual,step))<=0.003) {
                    predictor.reset(); quietPredictor.reset();
                    residual=add(residual,step); tiltAccumulated=add(tiltAccumulated,step); tiltRefinementCount++;
                }
            } else refiner.reset();
            qualify(s);
        }
        private void confirmSaved(GlassesImuParser.Sample s) {
            Double t=temperature.value; double[] expected=modelBias(t);
            if(!startupCheckAllowed || !validating || deskOnly || !biasReady || bootstrap!=null || t==null || expected==null || norm(sub(total,expected))>=0.0006) return;
            boolean nearby=profile.observations.stream().anyMatch(o->o.valid() && o.temperature()!=null && Math.abs(o.temperature()-t)<1);
            if(!nearby && (factory==null || factory.anchoredBias(profile,t)==null)) return;
            for(long span:new long[]{1_250_000_000L,2_250_000_000L,6_250_000_000L,18_250_000_000L}) {
                if(recent.isEmpty() || s.timeNanos-recent.get(0).sample.timeNanos<span-50_000_000L) continue;
                ArrayList<GlassesImuParser.Sample> samples=new ArrayList<>();
                for(Entry e:recent) if(s.timeNanos-e.sample.timeNanos<=span) samples.add(e.sample);
                if(TrackingRefinement.confirms(samples,expected)) {
                    validating=false; startupCheckAllowed=false; startupConfirmationSeconds=(s.timeNanos-streamStarted)*1e-9;
                    status="Saved baseline confirmed; refining in background"; return;
                }
            }
        }
        private void qualify(GlassesImuParser.Sample s) {
            if(norm(s.gyro())>=0.35 || norm(s.accel())<7.5 || norm(s.accel())>12) {
                candidate=null; restartWindow(); status="Tracking; waiting for a quiet validation interval"; return;
            }
            recent.add(new Entry(s,temperature.value)); if(recent.size()>21000) recent.subList(0,1000).clear();
            if(validating && !deskOnly && !extendedValidation && s.timeNanos-validationStarted>=8_000_000_000L) {
                // Keep the quick path for quiet starts; average several sway/heartbeat cycles
                // when short proposals cannot agree. Never lower the heading/uncertainty gates.
                extendedValidation=true; candidate=null; nextAttempt=0;
            }
            if(s.timeNanos<nextAttempt) return;
            nextAttempt=s.timeNanos+(extendedValidation?1_000_000_000L:250_000_000L);
            long retained=validating && !deskOnly?18_350_000_000L:3_100_000_000L;
            recent.removeIf(e->s.timeNanos-e.sample.timeNanos>retained);
            confirmSaved(s);
            if(!validating && extendedValidation) { extendedValidation=false; candidate=null; }
            if(candidate!=null && s.timeNanos-candidate.end>(extendedValidation?26_000_000_000L:8_000_000_000L)) candidate=null;
            tiltProposal=null;
            Proposal current=evaluate(2.05,true);
            if(current==null && !deskOnly) {
                Proposal shortFit=evaluate(3.05,false);
                // After a difficult start, only stable heading estimates may bypass sway averaging.
                if(shortFit!=null && (!extendedValidation || calibration.headingUncertainty<=0.0002)) current=shortFit;
            }
            if(current==null && extendedValidation) current=evaluate(12.05,false);
            if(current==null && extendedValidation) current=evaluate(18.05,false);
            if(!biasReady && !deskOnly && tiltProposal!=null) beginBootstrap(tiltProposal);
            if(current==null) { if(validating) status="Tracking active · "+calibration.status; return; }
            BiasProfile.Observation observed=current.observation;
            if(deskOnly && observed.temperature()==null) {
                candidate=null; status="Stationary calibration; waiting for stable sensor temperature"; return;
            }
            double[] up=unit(observed.gravity());
            BiasProfile.Observation trusted=profile.latest();
            if(recheckingTrusted && validating && trusted!=null && observed.uncertainty()<=0.0006 && observed.temperature()!=null && trusted.temperature()!=null
                    && Math.abs(observed.temperature()-trusted.temperature())<0.75) {
                double[] delta=sub(observed.bias(),trusted.bias()),applied=sub(observed.bias(),total);
                if(norm(delta)<0.0012 && Math.abs(dot(delta,up))<0.0004 && norm(applied)<0.0012 && Math.abs(dot(applied,up))<0.0004) {
                    validating=recheckingTrusted=false; status="Existing bias confirmed; refining in background";
                }
            }
            if((provisional==null || provisional.method().equals("tilt only; heading uncalibrated")) && trusted==null && !deskOnly
                    && observed.uncertainty()<=0.0006 && Math.abs(dot(observed.bias(),up))<=0.001) beginBootstrap(observed);
            if(candidate==null) { candidate=current; if(validating) status="Tracking provisionally; checking a separate interval"; return; }
            // Freeze the first qualified interval until a disjoint holdout arrives. Replacing
            // it with overlapping "better" fits can indefinitely move the completion target.
            if(current.start<=candidate.end) return;
            boolean bothStationary=current.stationaryQuality && candidate.stationaryQuality;
            BiasProfile.Observation previous=candidate.observation; double[] delta=sub(observed.bias(),previous.bias());
            if(norm(delta)>=0.0012 || Math.abs(dot(delta,up))>=0.0004 || observed.temperature()!=null && previous.temperature()!=null && Math.abs(observed.temperature()-previous.temperature())>=0.75) {
                candidate=current; rejections++; status="Intervals disagree; checking a newer interval"; return;
            }
            candidate=null;
            BiasProfile.Observation combined=new BiasProfile.Observation(mul(add(observed.bias(),previous.bias()),0.5),observed.gravity(),
                observed.temperature()!=null && previous.temperature()!=null?(observed.temperature()+previous.temperature())/2:null,
                Math.max(Math.max(observed.uncertainty(),previous.uncertainty()),norm(delta)/2),observed.duration()+previous.duration(),deskOnly?"desk + holdout":"worn + holdout");
            if(!combined.valid()) return;
            // A user-requested stationary calibration may replace stale bias only after
            // two disjoint quiet intervals agree, including measured gravity and temperature.
            boolean quietAgreement=bothStationary
                    && observed.method().equals("quiet 2 s") && previous.method().equals("quiet 2 s")
                    && combined.temperature()!=null && combined.uncertainty()<=0.0002
                    && norm(delta)<0.0004 && Math.abs(dot(delta,up))<0.0002
                    && dot(unit(observed.gravity()),unit(previous.gravity()))>Math.cos(Math.toRadians(0.1));
            boolean stationaryConfirmed=deskOnly && validating && quietAgreement;
            if(deskOnly && !stationaryConfirmed) {
                candidate=current; status="Stationary intervals need closer agreement; leave glasses on a stable surface"; return;
            }
            double[] trustedBias=profile.bias(combined.temperature());
            if(trustedBias!=null && !stationaryConfirmed) {
                double[] change=sub(combined.bias(),trustedBias);
                if(norm(change)>=0.003 || Math.abs(dot(change,up))>=(deskOnly?0.001:0.0004)) {
                    rejections++; status="Candidate differs from trusted bias; use desk calibration or forget"; return;
                }
            }
            boolean hadBaseline=ready,firstTrusted=trusted==null;
            profile.insert(combined); revision++; if(stationaryConfirmed) stationaryRevision=revision; refiner.reset(); tiltAccumulated=new double[3];
            double[] newBase=modelBias(temperature.value);
            if(hadBaseline) {
                residual=add(residual,sub(base,newBase)); base=newBase;
                if(firstTrusted || stationaryConfirmed) bootstrap=combined.bias().clone();
                else {
                    double[] targetResidual=sub(combined.bias(),newBase);
                    // Apply independently confirmed quiet estimates promptly during warm-up.
                    // This changes convergence only; the trusted-heading guard above still applies.
                    double responseSeconds=quietAgreement?5:30;
                    double[] step=limit(mul(sub(targetResidual,residual),1-Math.exp(-combined.duration()/responseSeconds)),0.0002);
                    residual=limit(add(residual,step),0.003);
                    predictor.reset(); quietPredictor.reset();
                }
            }
            else { base=newBase; residual=new double[3]; total=base.clone(); gravity=rotate(q,combined.gravity()); }
            if(stationaryConfirmed) {
                // Keep the displayed pose, but retire the old bias residual quickly and use
                // averaged stationary gravity instead of the startup sample's noisy reference.
                gravity=rotate(q,combined.gravity()); gravityCorrection.reset();
                gravityCorrectionActive=false; gravityErrorDegrees=0;
                predictor.reset(); quietPredictor.reset(); deskOnly=false;
            }
            ready=biasReady=true; validating=recheckingTrusted=extendedValidation=false; provisional=null; status="Calibrated · "+combined.method();
        }
        private void beginBootstrap(BiasProfile.Observation o) {
            residual=add(residual,sub(base,o.bias())); base=o.bias().clone(); bootstrap=base.clone();
            if(gravity==null) gravity=rotate(q,o.gravity());
            ready=biasReady=validating=true; provisional=o;
        }
        private Proposal evaluate(double seconds,boolean quietOnly) {
            if(recent.isEmpty()) return null;
            long end=recent.get(recent.size()-1).sample.timeNanos;
            ArrayList<Entry> entries=new ArrayList<>(); for(Entry e:recent) if((end-e.sample.timeNanos)*1e-9<=seconds) entries.add(e);
            double minimum=seconds>4?seconds-0.1:quietOnly?1.95:2.95;
            if(entries.isEmpty() || (end-entries.get(0).sample.timeNanos)*1e-9<minimum+0.02) return null;
            GravityCalibration fit=new GravityCalibration(false,seconds>4?minimum:1.95); Moments gyro=new Moments(),accel=new Moments();
            double tmin=Double.POSITIVE_INFINITY,tmax=Double.NEGATIVE_INFINITY,tsum=0; int tc=0;
            for(Entry e:entries) {
                gyro.add(e.sample.gyro()); accel.add(e.sample.accel());
                if(e.temperature!=null) { tmin=Math.min(tmin,e.temperature); tmax=Math.max(tmax,e.temperature); tsum+=e.temperature; tc++; }
                fit.accept(e.sample);
                if(!fit.ready) continue;
                calibration=fit;
                if(quietOnly && !fit.method.equals("quiet 2 s") || gyro.rms()>=(deskOnly?0.003:0.12) || accel.rms()>=(deskOnly?0.035:0.65) || tmax-tmin>=0.5) return null;
                Double t=tc>=gyro.count*0.9?tsum/tc:null;
                return new Proposal(new BiasProfile.Observation(fit.bias,fit.gravity,t,fit.uncertainty,fit.seconds(),fit.method),entries.get(0).sample.timeNanos,e.sample.timeNanos,
                        quietOnly && gyro.rms()<0.003 && accel.rms()<0.035);
            }
            calibration=fit;
            if(!quietOnly && !deskOnly && fit.tilt!=null && gyro.rms()<0.12 && accel.rms()<0.65) {
                GravityCalibration.Tilt t=fit.tilt;
                tiltProposal=new BiasProfile.Observation(t.bias(),t.gravity(),null,t.uncertainty(),t.duration(),"tilt only; heading uncalibrated");
            }
            return null;
        }
        double validationWindowSeconds() { return extendedValidation?18:3; }
        boolean calibrationWaiting() { return validating && (extendedValidation || candidate!=null || calibration.seconds()>=2.95); }
        int progress() { return ready && !validating?100:Math.min(95,(candidate==null?0:50)+calibration.progress()/2); }
        String method() { return profile.latest()!=null?profile.latest().method():provisional!=null?"provisional · "+provisional.method():"gyro tracking; bias unknown"; }
        double uncertainty() { return profile.latest()!=null?profile.latest().uncertainty():provisional!=null?provisional.uncertainty():calibration.uncertainty; }
        double[] quaternion() { return product(zero,q); }
        double[] bias() { return total.clone(); }
        double[] angularVelocity() { return velocity.clone(); }
        MotionPrediction prediction() { return predictor.prediction.order==0?MotionPrediction.linear(velocity,0,0.002):predictor.prediction; }
        MotionPrediction predictionV2a() { return quietPredictor.prediction.order==0?MotionPrediction.linear(velocity,0,0):quietPredictor.prediction; }
        double predictionV2aConfidence() { return quietPredictor.derivativeConfidence; }
        double[] angles() {
            double[] r=quaternion(); double w=r[0],x=r[1],y=r[2],z=r[3];
            return v(Math.toDegrees(Math.atan2(2*(x*z+w*y),1-2*(x*x+y*y))),
                Math.toDegrees(Math.asin(Math.max(-1,Math.min(1,2*(w*x-y*z))))),Math.toDegrees(Math.atan2(2*(x*y+w*z),1-2*(x*x+z*z))));
        }
    }

    // ---- Extracted from GravityCalibration.java ----
    /** Port of Mac XR's block-averaged, Huber-weighted gravity fit. A fit is not a trusted record. */
    static final class GravityCalibration {
        record Tilt(double[] bias,double[] gravity,double uncertainty,double duration) {}
        private record Block(double time,double[] gyro,double[] accel) {}
        private final ArrayList<Block> blocks=new ArrayList<>();
        private final boolean tiltOnly;
        private final double minimumSeconds,retainedSeconds;
        private long started,last;
        private int count;
        private double[] gyroSum=new double[3],accelSum=new double[3];
        private double nextAttempt;
        boolean ready;
        double[] bias=new double[3],gravity=new double[3];
        double uncertainty,headingUncertainty,gyroRms;
        Tilt tilt;
        String status="Look at a fixed point; small natural movements are OK",method="collecting";
        GravityCalibration() { this(false); }
        GravityCalibration(boolean tiltOnly) { this(tiltOnly,1.95); }
        GravityCalibration(boolean tiltOnly,double minimumSeconds) {
            this.tiltOnly=tiltOnly; this.minimumSeconds=minimumSeconds;
            retainedSeconds=Math.max(8,minimumSeconds+0.1);
        }
        double seconds() { return blocks.size()<2?0:blocks.get(blocks.size()-1).time-blocks.get(0).time; }
        int progress() { return ready?100:Math.min(95,(int)(seconds()/3*100)); }
        private void clearBlock() { started=0; count=0; gyroSum=new double[3]; accelSum=new double[3]; }
        private void clear(String message) { tilt=null; blocks.clear(); clearBlock(); nextAttempt=0; status=message; }
        void accept(GlassesImuParser.Sample s) {
            if(ready) return;
            if(last!=0) { if(s.timeNanos<=last) return; if(s.timeNanos-last>100_000_000) clear("Sensor gap; collecting fresh readings"); }
            last=s.timeNanos;
            double[] g=s.gyro(),a=s.accel();
            if(norm(g)>=0.35 || norm(a)<7.5 || norm(a)>12) { clear("Large movement; settle and look at a fixed point"); return; }
            if(started==0) started=s.timeNanos;
            count++; gyroSum=add(gyroSum,g); accelSum=add(accelSum,a);
            if(s.timeNanos-started<20_000_000) return;
            if(count<5) { clearBlock(); return; }
            blocks.add(new Block((started+(s.timeNanos-started)/2)*1e-9,mul(gyroSum,1.0/count),mul(accelSum,1.0/count)));
            clearBlock(); double now=blocks.get(blocks.size()-1).time;
            while(!blocks.isEmpty() && now-blocks.get(0).time>retainedSeconds) blocks.remove(0);
            // Also bound pathological high-density input independent of timestamps.
            while(blocks.size()>(int)(retainedSeconds/0.02)+2) blocks.remove(0);
            if(seconds()<minimumSeconds || now<nextAttempt) return;
            nextAttempt=now+0.25; qualify();
        }
        private double[] accelMean(int from,int to) {
            double[] sum=new double[3]; for(int i=from;i<to;i++) sum=add(sum,blocks.get(i).accel); return mul(sum,1.0/(to-from));
        }
        private void qualify() {
            tilt=null; int n=blocks.size(); double span=seconds();
            Moments gm=new Moments(),am=new Moments(); for(Block b:blocks) { gm.add(b.gyro); am.add(b.accel); }
            gyroRms=gm.rms()*Math.sqrt((n-1.0)/n); double accelRms=am.rms()*Math.sqrt((n-1.0)/n);
            double[] g=unit(am.mean), startG=unit(accelMean(0,5)),endG=unit(accelMean(n-5,n));
            if(!tiltOnly && gyroRms<0.0015 && accelRms<0.035 && dot(startG,endG)>Math.cos(Math.toRadians(0.1)) && acceptable(gm.mean,g)) {
                finish(gm.mean,"quiet 2 s",gyroRms/Math.sqrt(Math.max(1,span*10))); return;
            }
            if(span<2.95) { status="Small motion; estimating bias using gravity"; return; }
            if(gyroRms>=0.12 || accelRms>=0.65) { status="Waiting for gentler motion; recent readings roll forward"; return; }
            double[] q=identity(),initialG=unit(blocks.get(0).accel),times=new double[n],heading=new double[n];
            double[][] world=new double[n][];
            for(int i=0;i<n;i++) {
                Block b=blocks.get(i);
                if(i>0) q=integrate(q,mul(add(blocks.get(i-1).gyro,b.gyro),0.5),b.time-blocks.get(i-1).time);
                double[] a=unit(b.accel);
                if(dot(a,initialG)<=Math.cos(Math.toRadians(3))) { status="Head angle changed; gathering a fresh view"; return; }
                world[i]=rotate(q,a); heading[i]=dot(rotate(q,b.gyro),initialG); times[i]=b.time-blocks.get(0).time;
            }
            Fit fit=fit(times,world);
            double[] perpendicular=cross(unit(fit.mean),fit.slope);
            double hmean=Arrays.stream(heading).average().orElse(0);
            double[] candidate=add(perpendicular,mul(initialG,hmean));
            ArrayList<Double> means=new ArrayList<>(); double sum=0,boundary=1; int count=0;
            for(int i=0;i<n;i++) {
                if(times[i]>=boundary) { if(count>0) means.add(sum/count); sum=0; count=0; boundary+=1; }
                sum+=heading[i]; count++;
            }
            if(count>=20) means.add(sum/count);
            double average=0,variance=0; for(double x:means) average+=x/Math.max(1,means.size());
            for(double x:means) variance+=(x-average)*(x-average)/Math.max(1,means.size()-1);
            double headingSE=Math.sqrt(variance/Math.max(1,means.size()));
            headingUncertainty=headingSE;
            double tiltSE=Math.sqrt(5*fit.residualVariance/Math.max(1e-12,fit.timeVariance));
            double[] tiltBias=sub(perpendicular,mul(initialG,dot(perpendicular,initialG)));
            if(tiltSE<0.0006 && norm(tiltBias)<=0.012) tilt=new Tilt(tiltBias,accelMean(n-5,n),tiltSE,span);
            if(tiltOnly) return;
            uncertainty=Math.hypot(tiltSE,headingSE);
            double early=0,late=0; int mid=n/2;
            for(int i=0;i<n;i++) { if(i<mid) early+=heading[i]/mid; else late+=heading[i]/(n-mid); }
            if(uncertainty>=0.0012 || Math.abs(early-late)>=0.003) { status="Averaging small movements; keep looking at one point"; return; }
            if(!acceptable(candidate,initialG)) { status="Possible sustained turn; keep your gaze fixed"; return; }
            finish(candidate,"gravity-assisted rolling",uncertainty);
        }
        private boolean acceptable(double[] bias,double[] gravity) { return norm(bias)<=0.012 && Math.abs(dot(bias,gravity))<=0.002; }
        private void finish(double[] b,String m,double u) { bias=b.clone(); gravity=accelMean(blocks.size()-5,blocks.size()); method=m; uncertainty=u; ready=true; status="Candidate · "+m; }
        private record Fit(double[] mean,double[] slope,double residualVariance,double timeVariance) {}
        private static Fit fit(double[] times,double[][] values) {
            int n=times.length; double[] weights=new double[n]; Arrays.fill(weights,1);
            double[] mean=new double[3],slope=new double[3]; double tm=0,sxx=0;
            for(int round=0;round<4;round++) {
                double total=0; tm=0; mean=new double[3];
                for(int i=0;i<n;i++) { total+=weights[i]; tm+=weights[i]*times[i]; mean=add(mean,mul(values[i],weights[i])); }
                tm/=total; mean=mul(mean,1/total); sxx=0; slope=new double[3];
                for(int i=0;i<n;i++) { double t=times[i]-tm; sxx+=weights[i]*t*t; slope=add(slope,mul(sub(values[i],mean),weights[i]*t)); }
                slope=mul(slope,1/Math.max(1e-12,sxx));
                for(int i=0;i<n;i++) weights[i]=Math.min(1,0.003/Math.max(1e-12,norm(sub(sub(values[i],mean),mul(slope,times[i]-tm)))));
            }
            double residual=0;
            for(int i=0;i<n;i++) { double[] r=sub(sub(values[i],mean),mul(slope,times[i]-tm)); residual+=weights[i]*dot(r,r); }
            return new Fit(mean,slope,residual/Math.max(1,n-2),sxx);
        }
    }

    // ---- Extracted from TrackingRefinement.java ----
    static final class TrackingRefinement {
        /** Health check only: must never create a new record or lower its uncertainty. */
        static boolean confirms(List<GlassesImuParser.Sample> samples,double[] bias) {
            if(samples.size()<200 || (samples.get(samples.size()-1).timeNanos-samples.get(0).timeNanos)*1e-9<1.2) return false;
            double span=(samples.get(samples.size()-1).timeNanos-samples.get(0).timeNanos)*1e-9;
            // A long health check can include back-and-forth yaw. Keep the same net
            // heading-drift gate; only allow more reversible motion within each short block.
            double shortHeadingLimit=span>=6?0.04:0.01;
            ArrayList<GlassesImuParser.Sample> blocks=new ArrayList<>(),clean=new ArrayList<>();
            long begin=samples.get(0).timeNanos,previous=begin; int count=0;
            double[] gyro=new double[3],accel=new double[3];
            for(GlassesImuParser.Sample s:samples) {
                if(s.timeNanos<previous || s.timeNanos-previous>20_000_000 || norm(sub(s.gyro(),bias))>=0.2) return false;
                previous=s.timeNanos; gyro=add(gyro,s.gyro()); accel=add(accel,s.accel()); count++;
                if(s.timeNanos-begin>=20_000_000) {
                    blocks.add(sample(begin+(s.timeNanos-begin)/2,mul(gyro,1.0/count),mul(accel,1.0/count)));
                    gyro=new double[3]; accel=new double[3]; count=0; begin=s.timeNanos;
                }
            }
            for(GlassesImuParser.Sample s:blocks) if(Math.abs(norm(s.accel())-9.81)<0.25) clean.add(s);
            if(clean.size()<50 || clean.size()<0.95*blocks.size()) return false;
            double[] q=identity(); ArrayList<double[]> world=new ArrayList<>();
            Moments rates=new Moments(),accelerations=new Moments();
            double blockHeading=0,blockDuration=0,integral=0,duration=0;
            for(int i=0;i<clean.size();i++) {
                GlassesImuParser.Sample s=clean.get(i); double[] rate=sub(s.gyro(),bias);
                if(norm(rate)>=0.12) return false;
                rates.add(rate); accelerations.add(s.accel());
                if(i>0) {
                    GlassesImuParser.Sample old=clean.get(i-1); double dt=(s.timeNanos-old.timeNanos)*1e-9;
                    if(dt<=0 || dt>0.06) return false;
                    q=integrate(q,mul(add(rate,sub(old.gyro(),bias)),0.5),dt);
                    double heading=dot(rate,unit(s.accel())); integral+=heading*dt; duration+=dt; blockHeading+=heading*dt; blockDuration+=dt;
                    if(blockDuration>=0.25) { if(Math.abs(blockHeading/blockDuration)>=shortHeadingLimit) return false; blockDuration=blockHeading=0; }
                }
                world.add(rotate(q,unit(s.accel())));
            }
            if(duration<1.2 || rates.rms()>=0.08 || accelerations.rms()>=0.25 || Math.abs(integral/duration)>=0.0004) return false;
            int n=Math.max(1,clean.size()/10); double[] early=new double[3],late=new double[3];
            for(int i=0;i<n;i++) { early=add(early,world.get(i)); late=add(late,world.get(world.size()-1-i)); }
            return norm(cross(unit(early),unit(late)))/(duration*0.9)<0.0012;
        }
        static GlassesImuParser.Sample sample(long time,double[] g,double[] a) {
            return new GlassesImuParser.Sample(time,(float)g[0],(float)g[1],(float)g[2],(float)a[0],(float)a[1],(float)a[2]);
        }
        static final class TiltRefiner {
            private GravityCalibration fit=new GravityCalibration(true);
            private long started,previous;
            private Moments accel=new Moments();
            private int outliers;
            private double headingTravel;
            void reset() { fit=new GravityCalibration(true); started=previous=0; accel=new Moments(); outliers=0; headingTravel=0; }
            double[] accept(GlassesImuParser.Sample s,double[] totalBias) {
                double[] rate=sub(s.gyro(),totalBias),a=s.accel(); double magnitude=norm(a);
                if(magnitude<7.5 || magnitude>12 || norm(rate)>=0.12) { reset(); return null; }
                if(previous!=0 && (s.timeNanos<=previous || s.timeNanos-previous>20_000_000)) reset();
                if(previous!=0) headingTravel+=Math.abs(dot(rate,unit(a)))*(s.timeNanos-previous)*1e-9;
                previous=s.timeNanos; if(started==0) started=s.timeNanos;
                accel.add(a); if(Math.abs(magnitude-9.81)>=0.25) outliers++;
                fit.accept(sample(s.timeNanos,rate,a));
                if(s.timeNanos-started<3_100_000_000L) return null;
                double[] step=null;
                if(accel.rms()<0.25 && outliers<=0.05*accel.count && headingTravel<0.07 && fit.tilt!=null && fit.tilt.uncertainty()<0.0006) {
                    GravityCalibration.Tilt e=fit.tilt; double[] up=unit(a),delta=sub(e.bias(),mul(up,dot(e.bias(),up)));
                    if(norm(delta)<0.003) {
                        double confidence=Math.min(1,Math.pow(0.0003/Math.max(1e-12,e.uncertainty()),2));
                        step=limit(mul(delta,(1-Math.exp(-e.duration()/30))*confidence),0.0001);
                    }
                }
                reset(); return step;
            }
        }
    }

    // ---- Extracted from GravityTiltCorrection.java ----
    /** Mac XR's motion-compensated, block-averaged gravity observer. Never corrects heading. */
    static final class GravityTiltCorrection {
        private record Block(long time,double[] acceleration) {}
        private final ArrayDeque<Block> blocks=new ArrayDeque<>();
        private double[] sum=new double[3],direction;
        private int count;
        private long started;
        boolean active;
        double activeSeconds,errorDegrees;
        void reset() {
            blocks.clear(); sum=new double[3]; count=0; started=0; direction=null;
            active=false; errorDegrees=0;
        }
        void forget() { reset(); activeSeconds=0; }
        /** World-frame rate, applied after integrating this sample's body-frame gyro. */
        double[] accept(GlassesImuParser.Sample s,double[] orientation,double[] reference,double[] velocity,double dt) {
            active=false;
            double[] a=s.accel();
            if(dt<=0 || dt>0.02 || Math.abs(norm(a)-9.81)>=0.6 || norm(velocity)>=0.35) {
                reset(); return new double[3];
            }
            if(started==0) started=s.timeNanos;
            sum=add(sum,rotate(orientation,a)); count++;
            if(s.timeNanos-started>=20_000_000) {
                double[] mean=mul(sum,1.0/count);
                sum=new double[3]; count=0; started=0;
                if(Math.abs(norm(mean)-9.81)>=0.35) { reset(); return new double[3]; }
                blocks.addLast(new Block(s.timeNanos,mean));
                while(s.timeNanos-blocks.getFirst().time>600_000_000) blocks.removeFirst();
                Moments moments=new Moments();
                for(Block block:blocks) moments.add(block.acceleration);
                direction=s.timeNanos-blocks.getFirst().time>=500_000_000 && moments.rms()<0.10
                        && norm(sub(mean,moments.mean))<0.15?unit(moments.mean):null;
            }
            if(direction==null) return new double[3];
            double[] up=unit(reference),error=cross(direction,up);
            errorDegrees=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,dot(direction,up)))));
            active=true; activeSeconds+=dt;
            return mul(error,Math.min(0.5,0.005/Math.max(1e-12,norm(error))));
        }
    }

    // ---- Extracted from TemperatureFilter.java ----
    static final class TemperatureFilter {
        private Double previous,filtered;
        Double value;
        private double stableSeconds,windowSeconds;
        private final ArrayList<Double> samples=new ArrayList<>();
        void reset() { previous=filtered=value=null; stableSeconds=windowSeconds=0; samples.clear(); }
        void accept(Double t,double dt) {
            if(t==null || !Double.isFinite(t) || t<5 || t>85 || dt<=0 || dt>0.1) { reset(); return; }
            if(filtered!=null && Math.abs(t-filtered)>5) { reset(); return; }
            samples.add(t); windowSeconds+=dt;
            if(samples.size()>4096) { reset(); return; }
            if(windowSeconds<0.25) return;
            Collections.sort(samples); int trim=samples.size()/10; double mean=0;
            for(int i=trim;i<samples.size()-trim;i++) mean+=samples.get(i)/(samples.size()-2*trim);
            double elapsed=windowSeconds; samples.clear(); windowSeconds=0;
            if(previous!=null && Math.abs(mean-previous)>0.5+2*elapsed) { previous=filtered=mean; stableSeconds=0; value=null; return; }
            previous=mean; stableSeconds+=elapsed; filtered=filtered==null?mean:filtered+(1-Math.exp(-elapsed))*(mean-filtered);
            value=stableSeconds>=2?filtered:null;
        }
    }

    // ---- Extracted from BiasProfile.java ----
    /** Only independently validated observations belong in this model. */
    static final class BiasProfile {
        record Observation(double[] bias,double[] gravity,Double temperature,double uncertainty,double duration,String method) {
            boolean valid() { return bias.length==3 && gravity.length==3 && finite(bias) && finite(gravity) && norm(bias)<=0.012
                && norm(gravity)>=7.5 && norm(gravity)<=12 && Double.isFinite(uncertainty) && uncertainty>=0 && uncertainty<=0.0012
                && Double.isFinite(duration) && duration>=3.9 && (temperature==null || Double.isFinite(temperature) && temperature>=5 && temperature<=85); }
        }
        final ArrayList<Observation> observations=new ArrayList<>();
        boolean valid() { return observations.size()<=32 && observations.stream().allMatch(Observation::valid); }
        Observation latest() { return observations.isEmpty()?null:observations.get(observations.size()-1); }
        void insert(Observation v) {
            if(!v.valid()) return;
            observations.removeIf(o->v.temperature==null?o.temperature==null:o.temperature!=null && Math.abs(o.temperature-v.temperature)<0.5);
            observations.add(v); if(observations.size()>32) observations.remove(0);
        }
        double[] bias(Double t) {
            if(latest()==null) return null;
            if(t==null) return latest().bias.clone();
            ArrayList<Observation> bins=new ArrayList<>(); for(Observation o:observations) if(o.temperature!=null) bins.add(o);
            if(bins.isEmpty()) return latest().bias.clone();
            bins.sort(Comparator.comparingDouble(o->o.temperature));
            Observation nearest=bins.get(0),lower=null,upper=null;
            for(Observation o:bins) {
                if(Math.abs(o.temperature-t)<Math.abs(nearest.temperature-t)) nearest=o;
                if(o.temperature<=t) lower=o;
                if(o.temperature>=t && upper==null) upper=o;
            }
            if(lower!=null && upper!=null) {
                double span=upper.temperature-lower.temperature;
                if(span>0 && span<=5) return add(lower.bias,mul(sub(upper.bias,lower.bias),(t-lower.temperature)/span));
            }
            return nearest.bias.clone();
        }
    }

    // ---- Extracted from FactoryBiasTable.java ----
    /** Empirically guarded thermal delta, never a factory-only or double-subtracted total bias. */
    static final class FactoryBiasTable {
        record Record(double temperature,double[] bias) {}
        final List<Record> records;
        FactoryBiasTable(List<Record> records) {
            this.records=Collections.unmodifiableList(new ArrayList<>(records));
            if(records.size()<2 || records.size()>200) throw new IllegalArgumentException("Factory table size");
            Record prior=null;
            for(Record r:records) {
                if(!Double.isFinite(r.temperature) || r.temperature<5 || r.temperature>85 || r.bias.length!=3 || !finite(r.bias) || norm(r.bias)>0.012
                        || prior!=null && (r.temperature<=prior.temperature || r.temperature-prior.temperature>5)) throw new IllegalArgumentException("Factory table values");
                prior=r;
            }
        }
        boolean contains(double t) { return t>=records.get(0).temperature && t<=records.get(records.size()-1).temperature; }
        double[] bias(double t) {
            if(t<=records.get(0).temperature) return records.get(0).bias.clone();
            for(int i=1;i<records.size();i++) {
                Record low=records.get(i-1),high=records.get(i);
                if(t<=high.temperature) return add(low.bias,mul(sub(high.bias,low.bias),(t-low.temperature)/(high.temperature-low.temperature)));
            }
            return records.get(records.size()-1).bias.clone();
        }
        double[] anchoredBias(BiasProfile profile,Double t) {
            if(!profile.valid() || t==null) return null;
            BiasProfile.Observation anchor=null;
            for(BiasProfile.Observation o:profile.observations) {
                if(o.temperature()==null || !contains(o.temperature()) || Math.abs(t-o.temperature())>3 || o.uncertainty()>0.0006) continue;
                double[] f=bias(o.temperature()); double magnitude=norm(f),difference=norm(sub(o.bias(),f));
                if(magnitude<0.003 || difference>0.002 || difference>=magnitude*0.4) continue;
                if(anchor==null || Math.abs(o.temperature()-t)<Math.abs(anchor.temperature()-t)) anchor=o;
            }
            if(anchor==null) return null;
            double[] delta=sub(bias(t),bias(anchor.temperature()));
            return norm(delta)<=0.0005?add(anchor.bias(),delta):null;
        }
    }

    // ---- Extracted from FactoryConfigReply.java ----
    /** Bounded decoder for the single allowlisted read-only NRGlassesGetConfig request. */
    static final class FactoryConfigReply {
        static final byte[] REQUEST={0x27,0x1f,0,0,0,6,(byte)0x80,0,0,1,0x18,0};
        static final int MAX_FRAME=4*1024*1024;
        private static final class Buffer extends ByteArrayOutputStream {
            byte[] array() { return buf; }
        }
        private final Buffer pending=new Buffer();
        private int consumed,unrelated;
        private boolean finished;
        byte[] append(byte[] chunk,int count) throws IOException {
            if(finished) return null;
            consumed+=count;
            if(count<0 || count>chunk.length || consumed>8*1024*1024 || pending.size()+count>MAX_FRAME+6+8192) throw new IOException("Config framing limit");
            pending.write(chunk,0,count);
            // Avoid copying the growing body on every TCP chunk. Peek only the six-byte header.
            while(pending.size()>=6) {
                byte[] all=pending.array(); int available=pending.size();
                int command=((all[0]&255)<<8)|(all[1]&255);
                long length=0; for(int i=2;i<6;i++) length=(length<<8)|(all[i]&255);
                if(length>MAX_FRAME) throw new IOException("Config frame too large");
                if(available<length+6) return null;
                byte[] body=Arrays.copyOfRange(all,6,6+(int)length);
                pending.reset(); pending.write(all,6+(int)length,available-6-(int)length);
                if(command==0x271f && body.length>=4 && body[0]==0 && body[1]==0 && body[2]==0 && body[3]==1) {
                    List<Field> response=fields(one(fields(Arrays.copyOfRange(body,4,body.length)),4));
                    int statuses=0;
                    for(Field f:response) if(f.number==1) { statuses++; if(f.wire!=0 || f.integer!=0 || statuses>1) throw new IOException("Config getter status"); }
                    byte[] json=one(response,2); finished=true; return json;
                }
                if(++unrelated>256) throw new IOException("Too many unrelated config frames");
            }
            return null;
        }
        private record Field(long number,int wire,long integer,byte[] bytes) {}
        private static long varint(byte[] data,int[] cursor) throws IOException {
            long value=0;
            for(int i=0;i<10;i++) {
                if(cursor[0]>=data.length) throw new IOException("Truncated protobuf");
                int b=data[cursor[0]++]&255;
                if(i==9 && b>1) throw new IOException("Protobuf overflow");
                value|=(long)(b&127)<<(i*7);
                if((b&128)==0) return value;
            }
            throw new IOException("Invalid protobuf varint");
        }
        private static List<Field> fields(byte[] data) throws IOException {
            int[] cursor={0}; List<Field> out=new ArrayList<>();
            while(cursor[0]<data.length) {
                if(out.size()>=1024) throw new IOException("Protobuf field limit");
                long key=varint(data,cursor),number=key>>>3; int wire=(int)(key&7);
                if(number==0 || number>0x1fffffff) throw new IOException("Invalid protobuf key");
                if(wire==0) { out.add(new Field(number,wire,varint(data,cursor),new byte[0])); continue; }
                long size;
                switch(wire) { case 1:size=8;break; case 2:size=varint(data,cursor);break; case 5:size=4;break; default:throw new IOException("Invalid protobuf wire"); }
                if(size<0 || size>data.length-cursor[0]) throw new IOException("Truncated protobuf value");
                out.add(new Field(number,wire,0,Arrays.copyOfRange(data,cursor[0],cursor[0]+(int)size))); cursor[0]+=(int)size;
            }
            return out;
        }
        private static byte[] one(List<Field> fields,long number) throws IOException {
            Field found=null;
            for(Field f:fields) if(f.number==number) { if(found!=null || f.wire!=2) throw new IOException("Ambiguous protobuf field"); found=f; }
            if(found==null) throw new IOException("Missing protobuf field"); return found.bytes;
        }
    }

    // ---- Extracted from CalibrationStore.java ----
    /** App-private, no-backup storage. Identity is re-established by a fresh getter on every connection. */
    static final class CalibrationStore {
        record Config(String key,FactoryBiasTable table) {}
        private final File root;
        CalibrationStore(Context context) { root=new File(context.getNoBackupFilesDir(),"imu-calibration"); }
        static Config decode(byte[] json) throws Exception {
            if(json.length>FactoryConfigReply.MAX_FRAME) throw new IllegalArgumentException("Configuration size");
            String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(json)).toString();
            JSONObject data=new JSONObject(text),imu=data.getJSONObject("IMU"); String serial=data.getString("FSN");
            if(serial.trim().isEmpty() || serial.getBytes(StandardCharsets.UTF_8).length>256 || imu.getInt("num_of_imus")!=1) throw new IllegalArgumentException("Configuration identity");
            JSONArray rows=imu.getJSONObject("device_1").getJSONArray("gyro_bias_temp_data");
            ArrayList<FactoryBiasTable.Record> records=new ArrayList<>();
            for(int i=0;i<rows.length();i++) { JSONObject row=rows.getJSONObject(i); records.add(new FactoryBiasTable.Record(row.getDouble("temp"),vector(row.getJSONArray("bias")))); }
            String descriptor="android-adaptive-v1:2836:rad-s:body-right-wxyz:single-imu:"+serial+":"+hash(json);
            return new Config(hash(descriptor.getBytes(StandardCharsets.UTF_8)),new FactoryBiasTable(records));
        }
        private static String hash(byte[] bytes) throws Exception {
            StringBuilder s=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) s.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return s.toString();
        }
        private AtomicFile file(String key,int sensor) { return new AtomicFile(new File(root,key+"-imu-"+sensor+".json")); }
        BiasProfile load(String key,int sensor) {
            try {
                AtomicFile file=file(key,sensor);
                if(file.getBaseFile().length()>65536) return new BiasProfile();
                byte[] bytes=file.readFully(); if(bytes.length>65536) return new BiasProfile();
                JSONObject data=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
                long age=System.currentTimeMillis()-data.getLong("savedAt");
                if(data.getInt("schema")!=1 || age<0 || age>30L*86400000) return new BiasProfile();
                JSONArray rows=data.getJSONArray("observations"); if(rows.length()>32) return new BiasProfile();
                BiasProfile profile=new BiasProfile();
                for(int i=0;i<rows.length();i++) {
                    JSONObject row=rows.getJSONObject(i);
                    BiasProfile.Observation o=new BiasProfile.Observation(vector(row.getJSONArray("bias")),vector(row.getJSONArray("gravity")),
                        row.isNull("temperature")?null:row.getDouble("temperature"),row.getDouble("uncertainty"),row.getDouble("duration"),row.getString("method"));
                    if(!o.valid()) return new BiasProfile(); profile.observations.add(o);
                }
                return profile.valid()?profile:new BiasProfile();
            } catch(Exception ignored) { return new BiasProfile(); }
        }
        private static double[] vector(JSONArray a) throws Exception {
            if(a.length()!=3) throw new IllegalArgumentException("Vector size"); return new double[]{a.getDouble(0),a.getDouble(1),a.getDouble(2)};
        }
        void save(String key,int sensor,BiasProfile profile) throws Exception {
            if(!profile.valid() || profile.latest()==null) return;
            JSONArray rows=new JSONArray();
            for(BiasProfile.Observation o:profile.observations) rows.put(new JSONObject().put("bias",new JSONArray(o.bias())).put("gravity",new JSONArray(o.gravity()))
                    .put("temperature",o.temperature()==null?JSONObject.NULL:o.temperature()).put("uncertainty",o.uncertainty()).put("duration",o.duration()).put("method",o.method()));
            byte[] bytes=new JSONObject().put("schema",1).put("savedAt",System.currentTimeMillis()).put("observations",rows).toString().getBytes(StandardCharsets.UTF_8);
            if(!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("Calibration directory");
            android.system.Os.chmod(root.getPath(),0700);
            AtomicFile file=file(key,sensor); FileOutputStream output=null;
            try {
                output=file.startWrite(); android.system.Os.fchmod(output.getFD(),0600); output.write(bytes); file.finishWrite(output);
            } catch(Exception error) { if(output!=null) file.failWrite(output); throw error; }
        }
        void forget(String key,int sensor) { file(key,sensor).delete(); }
    }

    // ---- Extracted from FactoryCalibrationSession.java ----
    /** Bounded background getter and serialized private IO. No network or disk IO on the IMU loop. */
    static final class FactoryCalibrationSession implements AutoCloseable {
        record Loaded(FactoryBiasTable table,BiasProfile profile) {}
        private final CalibrationStore store;
        private final ExecutorService io=Executors.newSingleThreadExecutor(r->new Thread(r,"XrealCalibration"));
        private volatile Socket socket;
        private CalibrationStore.Config config;
        private int sensor=-1,generation;
        private boolean closed,selected,compatible;
        private Loaded loaded;
        volatile String status="Reading device calibration in background";
        FactoryCalibrationSession(Context context,Network network,String host) {
            store=new CalibrationStore(context);
            io.execute(()->{
                try(Socket connection=network.getSocketFactory().createSocket()) {
                    synchronized(this) { if(closed) return; socket=connection; }
                    connection.connect(new InetSocketAddress(host,52999),1500); connection.setSoTimeout(1000);
                    connection.getOutputStream().write(FactoryConfigReply.REQUEST);
                    FactoryConfigReply reply=new FactoryConfigReply(); byte[] chunk=new byte[8192];
                    long deadline=System.nanoTime()+8_000_000_000L;
                    while(System.nanoTime()<deadline) {
                        int n;
                        try { n=connection.getInputStream().read(chunk); } catch(java.net.SocketTimeoutException e) { continue; }
                        if(n<0) throw new IOException("Getter disconnected");
                        byte[] json=reply.append(chunk,n);
                        if(json!=null) {
                            CalibrationStore.Config decoded=CalibrationStore.decode(json);
                            synchronized(this) { if(!closed) { config=decoded; status="Device identity verified"; } }
                            return;
                        }
                    }
                    throw new IOException("Getter deadline");
                } catch(Exception e) { status="Factory unavailable; using host calibration"; }
                finally { socket=null; }
            });
        }
        synchronized void select(int command,int sensor) {
            if(selected || closed || command<0) return;
            selected=true; this.sensor=sensor;
            if(command!=0x28) { status="Legacy report; saved calibration disabled"; return; }
            compatible=true;
            int token=generation;
            io.execute(()->{
                CalibrationStore.Config selectedConfig;
                synchronized(this) { if(closed || token!=generation || config==null) return; selectedConfig=config; }
                BiasProfile saved=store.load(selectedConfig.key(),sensor);
                synchronized(this) { if(!closed && token==generation) loaded=new Loaded(selectedConfig.table(),saved); }
            });
        }
        synchronized Loaded takeLoaded() { Loaded value=loaded; loaded=null; return value; }
        synchronized boolean save(BiasProfile profile) {
            if(closed || config==null || !compatible || sensor<0) return false;
            int token=generation,selectedSensor=sensor; String key=config.key();
            BiasProfile copy=new BiasProfile(); copy.observations.addAll(profile.observations);
            io.execute(()->{
                synchronized(this) { if(closed || generation!=token) return; }
                try { store.save(key,selectedSensor,copy); } catch(Exception e) { status="Calibration save failed; tracking continues"; }
            });
            return true;
        }
        synchronized void forget() {
            generation++; loaded=null;
            if(closed) return;
            // Queued after getter/load/save; generation prevents any old result being installed or saved.
            io.execute(()->{
                CalibrationStore.Config selectedConfig; int selectedSensor;
                synchronized(this) { selectedConfig=config; selectedSensor=sensor; }
                if(selectedConfig!=null && selectedSensor>=0) store.forget(selectedConfig.key(),selectedSensor);
            });
        }
        @Override public synchronized void close() {
            closed=true; generation++; loaded=null;
            if(socket!=null) try { socket.close(); } catch(IOException ignored) {}
            io.shutdown();
        }
    }

    // ---- Extracted from GlassesTracker.java ----
    /** Read-only TCP 52998 client. Only the IMU socket is bound; the process network never changes. */
    static final class GlassesTracker {
        static final class Reading {
            final String status,endpoint,calibrationMethod,factoryStatus;
            final long receivedAt,bytes,samples,otherReports,invalidReports,discardedBytes,gaps,rejectedTimes,duplicates,clockResets,otherSensors;
            final double receiptSeconds,sampleSeconds,excessDelayMs,clockRate;
            final double yaw,pitch,roll,hz,gravityErrorDegrees,calibrationBiasSpread,validationWindowSeconds;
            final boolean calibrated,trackingReady,biasReady,gravityCorrectionActive,clockReady,calibrationWaiting;
            final int progress,calibrationRejections,temperatureRecords,tiltRefinementCount,stationaryCalibrationRevision;
            final Double temperature,startupConfirmationSeconds;
            final double[] bias,quaternion,angularVelocity;
            final MotionPrediction predictionV2,predictionV2a;
            final double predictionV2aConfidence;
            Reading(String status,String endpoint,ImuStream stream,double hz,String factoryStatus) {
                AdaptiveHeadPose pose=stream.pose; GlassesImuParser parser=stream.parser; SampleGate gate=stream.gate;
                this.status=status; this.endpoint=endpoint; this.hz=hz; this.factoryStatus=factoryStatus;
                receiptSeconds=stream.lastAcceptedArrival; sampleSeconds=stream.predictionOrigin();
                // Kept for UI diagnostics; freshness/prediction use CLOCK_MONOTONIC seconds directly.
                receivedAt=receiptSeconds==0?0:SystemClock.elapsedRealtime()-(long)((System.nanoTime()*1e-9-receiptSeconds)*1000);
                bytes=parser.bytes; samples=gate.accepted; otherReports=parser.otherReports;
                invalidReports=parser.invalidReports; discardedBytes=parser.discardedBytes;
                gaps=gate.gaps; rejectedTimes=gate.duplicates+gate.backwards; duplicates=gate.duplicates; clockResets=gate.resets; otherSensors=gate.otherSensors;
                double[] angles=pose.angles(); yaw=angles[0]; pitch=angles[1]; roll=angles[2];
                calibrated=pose.ready && !pose.validating; trackingReady=pose.ready; biasReady=pose.biasReady;
                validationWindowSeconds=pose.validationWindowSeconds(); calibrationWaiting=pose.calibrationWaiting(); progress=pose.progress(); bias=pose.bias(); quaternion=pose.quaternion(); angularVelocity=pose.angularVelocity();
                predictionV2=pose.prediction(); predictionV2a=pose.predictionV2a(); predictionV2aConfidence=pose.predictionV2aConfidence();
                gravityCorrectionActive=pose.gravityCorrectionActive; gravityErrorDegrees=pose.gravityErrorDegrees;
                stationaryCalibrationRevision=pose.stationaryRevision; calibrationRejections=pose.rejections; calibrationBiasSpread=pose.uncertainty(); calibrationMethod=pose.method();
                temperature=pose.temperature.value; temperatureRecords=pose.profile.observations.size(); tiltRefinementCount=pose.tiltRefinementCount;
                startupConfirmationSeconds=pose.startupConfirmationSeconds;
                clockReady=Double.isFinite(stream.clock.sampleHostTime); excessDelayMs=stream.clock.excessDelayMs; clockRate=stream.clock.rate;
            }
            MotionPrediction prediction(PredictionModel model) {
                return switch(model) { case V1 -> MotionPrediction.linear(angularVelocity); case V2 -> predictionV2; case V2A -> predictionV2a; };
            }
            boolean fresh() { return SampleClock.fresh(receiptSeconds,sampleSeconds,System.nanoTime()*1e-9); }
            boolean ready() { return trackingReady && fresh(); }
        }
        private final Context context;
        private volatile Reading reading=new Reading("Sensor check stopped","",new ImuStream(),0,"Not connected");
        private volatile boolean running;
        private volatile Socket socket;
        private Thread worker;
        private volatile int epoch;
        private boolean wanted;
        private volatile boolean recalibrate, recenter, deskCalibration, forget;
        GlassesTracker(Context context) { this.context = context; }
        Reading reading() { return reading; }
        boolean wanted() { return wanted; }
        void start() { wanted = true; resume(); }
        synchronized void resume() {
            if (!wanted || running) return;
            running = true;
            int session = ++epoch;
            worker = new Thread(() -> run(session), "XrealImu");
            worker.start();
        }
        void stop() { wanted = false; pause(); }
        synchronized void pause() {
            running = false;
            epoch++;
            Socket active = socket;
            if (active != null) try { active.close(); } catch (IOException ignored) { }
            if (worker != null) worker.interrupt();
            // Invalidate the published pose immediately, including any queued rendering frame.
            reading = new Reading(wanted ? "Sensor check paused in background" : "Sensor check stopped",
                    "",new ImuStream(),0,"Not connected");
        }
        void recalibrate() { deskCalibration=false; recalibrate=true; }
        void calibrateDesk() { deskCalibration=true; recalibrate=true; }
        void forgetCalibration() { forget=true; }
        void recenter() { recenter = true; }
        private boolean active(int session) { return running && session == epoch; }

        private boolean glassesAttached() {
            for (UsbDevice d : context.getSystemService(UsbManager.class).getDeviceList().values()) {
                if (d.getVendorId() == 0x3318 && (d.getProductId() == 0x043e || d.getProductId() == 0x043d)) return true;
            }
            return false;
        }
        private synchronized void publish(int session, Reading next) {
            if (!active(session)) return;
            if (!reading.status.equals(next.status) || !reading.endpoint.equals(next.endpoint))
                Log.i("Xreal3Dof", "Glasses IMU: " + next.status + " " + next.endpoint);
            reading = next;
        }
        @SuppressWarnings("deprecation")
        private void run(int session) {
            // All parser and pose mutations belong to this worker. A replacement worker has its own pose.
            ImuStream stream = new ImuStream();
            while (active(session)) {
                boolean candidateFound = false;
                try {
                    if (!glassesAttached()) {
                        publish(session, new Reading("No XREAL 1S USB device; connect glasses to the phone", "",new ImuStream(),0,"Not connected"));
                    } else {
                        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
                        for (Network network : cm.getAllNetworks()) {
                            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                            LinkProperties link = cm.getLinkProperties(network);
                            if (caps == null || link == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
                            String host = null;
                            for (android.net.LinkAddress address : link.getLinkAddresses()) {
                                String local = address.getAddress().getHostAddress();
                                if (local != null && local.startsWith("169.254.2.")) host = "169.254.2.1";
                                else if (local != null && local.startsWith("169.254.1.")) host = "169.254.1.1";
                            }
                            if (host == null || !active(session)) continue;
                            candidateFound = true;
                            String endpoint = link.getInterfaceName() + " → " + host + ":52998";
                            stream = new ImuStream();
                            publish(session,new Reading("Connecting to glasses IMU",endpoint,stream,0,"Reading device calibration"));
                            try (Socket connection = network.getSocketFactory().createSocket()) {
                                synchronized (this) {
                                    if (!active(session)) return;
                                    socket = connection;
                                }
                                connection.connect(new InetSocketAddress(host, 52998), 1500);
                                connection.setSoTimeout(1000);
                                connection.setTcpNoDelay(true);
                                try(FactoryCalibrationSession factory=new FactoryCalibrationSession(context,network,host)) {
                                    read(session,connection.getInputStream(),endpoint,stream,factory);
                                }
                            } catch (IOException e) {
                                publish(session, new Reading("No live IMU: " + e.getClass().getSimpleName()
                                        + "; retrying", endpoint,new ImuStream(),0,"Not connected"));
                            } finally {
                                synchronized (this) { if (active(session)) socket = null; }
                            }
                        }
                        if (!candidateFound) publish(session, new Reading(
                                "XREAL USB found, no sensor network. Enable glasses TCP/IP and reconnect.", "",new ImuStream(),0,"Not connected"));
                    }
                } catch (RuntimeException e) {
                    publish(session, new Reading("Sensor error: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                            "",new ImuStream(),0,"Not connected"));
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }
        private void read(int session,InputStream input,String endpoint,ImuStream stream,FactoryCalibrationSession factory) throws IOException {
            byte[] chunk=new byte[8192];
            long rateStart=SystemClock.elapsedRealtime(),rateCount=0,lastPublish=0,lastLog=0,connectedAt=rateStart,lastSave=0;
            int savedRevision=0; boolean savedTemperature=false; double hz=0;
            while(active(session)) {
                int count=input.read(chunk);
                double arrival=System.nanoTime()*1e-9; // One timestamp per receive, shared by all batched samples.
                if(!active(session)) return;
                if(count<0) throw new IOException("IMU disconnected");
                if(forget) { factory.forget(); stream.pose.forget(); savedRevision=stream.pose.revision; savedTemperature=false; lastSave=0; forget=false; }
                if(recalibrate) { stream.pose.recalibrate(deskCalibration); recalibrate=false; }
                if(recenter) { stream.pose.recenter(); recenter=false; }
                FactoryCalibrationSession.Loaded loaded=factory.takeLoaded();
                if(loaded!=null) stream.pose.installFactory(loaded.table(),loaded.profile());
                stream.append(chunk,count,arrival);
                factory.select(stream.gate.command,stream.gate.sensor);
                long now=SystemClock.elapsedRealtime();
                if(now-rateStart>=1000) { hz=(stream.gate.accepted-rateCount)*1000.0/(now-rateStart); rateCount=stream.gate.accepted; rateStart=now; }
                if((stream.lastAcceptedArrival==0 && now-connectedAt>1500) || (stream.lastAcceptedArrival>0 && arrival-stream.lastAcceptedArrival>1))
                    throw new IOException("No advancing valid IMU timestamps");
                boolean hasTemperature=stream.pose.profile.observations.stream().anyMatch(o->o.temperature()!=null);
                if(stream.pose.revision!=savedRevision && stream.pose.profile.latest()!=null
                        && (stream.pose.stationaryRevision>savedRevision || lastSave==0 || hasTemperature && !savedTemperature || now-lastSave>=60000)) {
                    if(factory.save(stream.pose.profile)) { savedRevision=stream.pose.revision; lastSave=now; savedTemperature=hasTemperature; }
                }
                if(now-lastPublish<4) continue;
                lastPublish=now;
                String status=stream.lastAcceptedArrival==0?"Connected; waiting for valid IMU reports":stream.pose.status;
                Reading next=new Reading(status,endpoint,stream,hz,factory.status); publish(session,next);
                if(now-lastLog>=5000 && active(session)) {
                    lastLog=now;
                    Log.i("Xreal3Dof",String.format(Locale.US,
                        "IMU unique=%d Hz=%.1f ready=%s calibrated=%s yaw=%.2f pitch=%.2f roll=%.2f clock=%s excessMs=%.2f tiltUpdates=%d",
                        next.samples,hz,next.trackingReady,next.calibrated,next.yaw,next.pitch,next.roll,next.clockReady,next.excessDelayMs,next.tiltRefinementCount));
                }
            }
        }
    }

    // ---- Extracted from MotionPrediction.java ----
    /** Immutable body-frame SI coefficients. CPU quaternions are WXYZ; shaders upload XYZW.
     * Keep the bounded polynomial identical in PlaneShader and EglPlaneRenderer. */
    static final class MotionPrediction {
        static final MotionPrediction NONE = new MotionPrediction(new double[3],new double[3],new double[3],0,0);
        final double rx,ry,rz,ax,ay,az,jx,jy,jz,derivativeLimitFloor;
        final int order;
        MotionPrediction(double[] rate,double[] acceleration,double[] jerk,int order,double floor) {
            rx=rate[0]; ry=rate[1]; rz=rate[2]; ax=acceleration[0]; ay=acceleration[1]; az=acceleration[2];
            jx=jerk[0]; jy=jerk[1]; jz=jerk[2]; this.order=order; derivativeLimitFloor=floor;
        }
        static MotionPrediction linear(double[] rate) { return linear(rate,1,0.002); }
        static MotionPrediction linear(double[] rate,int order,double floor) {
            return new MotionPrediction(rate,new double[3],new double[3],order,floor);
        }
        double[] rate() { return v(rx,ry,rz); }
        double[] acceleration() { return v(ax,ay,az); }
        double[] jerk() { return v(jx,jy,jz); }
        static double[] limitNorm(double[] v,double bound) {
            return mul(v,Math.min(1,Math.max(0,bound)/Math.max(1e-15,norm(v))));
        }
        double[] rotationVector(double seconds) {
            if(!Double.isFinite(seconds)) return new double[3];
            double t=Math.max(-0.03,Math.min(0.05,seconds));
            double[] linear=mul(rate(),t), extra=add(mul(acceleration(),t*t/2),mul(jerk(),t*t*t/6));
            return add(linear,limitNorm(extra,Math.min(0.035,0.5*norm(linear)+derivativeLimitFloor)));
        }
        void predict(double[] q,double seconds,double[] out) {
            double[] r=rotationVector(seconds); double angle=norm(r);
            if(angle<=1e-15) { System.arraycopy(q,0,out,0,4); return; }
            double f=Math.sin(angle/2)/angle;
            double[] result=product(q,new double[]{Math.cos(angle/2),r[0]*f,r[1]*f,r[2]*f});
            double length=0; for(double x:result) length+=x*x; length=Math.sqrt(length);
            for(int i=0;i<4;i++) out[i]=result[i]/length;
        }
    }

    // ---- Extracted from AnchorPredictionFilter.java ----
    /** Timestamp-aware port of Mac XR's Anchor-derived cascade; not firmware bit parity. */
    static final class AnchorPredictionFilter {
        private long previousTime=-1,previousFrame=-1;
        private double[] previousRate=new double[3],previousBias=new double[3];
        private double[] rate=new double[3],acceleration=new double[3],jerk=new double[3];
        private int stage;
        MotionPrediction prediction=MotionPrediction.linear(new double[3],0,0.002);
        MotionPrediction cascadePrediction=prediction;
        void reset() {
            previousTime=previousFrame=-1; stage=0;
            previousRate=new double[3]; previousBias=new double[3]; rate=new double[3]; acceleration=new double[3]; jerk=new double[3];
            prediction=MotionPrediction.linear(rate,0,0.002); cascadePrediction=prediction;
        }
        /** Positive body increment; firmware conjugates for its inverse-pose/left-multiply convention. */
        static double[] increment(double[] a,double[] b,double dt) {
            double[] mean=mul(add(a,b),0.5); double n=norm(mean);
            double s=n>=0.0001?Math.sin(n*dt/2)/n:dt/2,c=n>=0.0001?Math.cos(n*dt/2):1;
            double[] xyz=add(mul(mean,s),mul(cross(a,b),dt*dt/24));
            double length=Math.sqrt(c*c+dot(xyz,xyz)), sign=c<0?-1:1;
            return new double[]{sign*c/length,sign*xyz[0]/length,sign*xyz[1]/length,sign*xyz[2]/length};
        }
        static double[] rotationVector(double[] q) {
            double[] xyz=v(q[1],q[2],q[3]); double n=norm(xyz);
            return mul(xyz,n>1e-15?2*Math.atan2(n,q[0])/n:2); // no firmware angle deadband
        }
        void accept(long time,long frame,double[] current,double[] bias) {
            if(!finite(current) || !finite(bias)) { reset(); return; }
            if(previousTime>=0 && time<=previousTime) return;
            double dt=previousTime>=0?(time-previousTime)*1e-9:0;
            boolean broken=dt<0.00025 || dt>0.0025 ||
                    frame>=0 && previousFrame>=0 && ((frame-previousFrame)&0xffffffffL)!=1 ||
                    norm(sub(bias,previousBias))>0.0102*dt+1e-12;
            double[] before=previousRate;
            previousTime=time; previousFrame=frame; previousRate=current.clone(); previousBias=bias.clone();
            if(broken) {
                stage=0; rate=current.clone(); acceleration=new double[3]; jerk=new double[3];
                prediction=MotionPrediction.linear(current,0,0.002); cascadePrediction=prediction; return;
            }
            double[] input=mul(rotationVector(increment(before,current,dt)),1/dt);
            if(stage==0) {
                rate=input; stage=1; prediction=MotionPrediction.linear(current,0,0.002); cascadePrediction=prediction; return;
            }
            double[] oldRate=rate,oldAcceleration=acceleration;
            double f1=stage>=2?Math.min(500,20+20*norm(acceleration)):500;
            double f2=stage>=3?Math.min(150,20+0.2*norm(jerk)):150;
            rate=add(rate,mul(sub(input,rate),alpha(f1,dt)));
            double[] difference=mul(sub(rate,oldRate),1/dt);
            acceleration=stage==1?difference:add(acceleration,mul(sub(difference,acceleration),alpha(f2,dt)));
            double[] secondDifference=mul(sub(acceleration,oldAcceleration),1/dt);
            if(stage==2) jerk=secondDifference;
            else if(stage>=3) jerk=add(jerk,mul(sub(secondDifference,jerk),alpha(15,dt)));
            int order=Math.min(3,stage); stage=Math.min(3,stage+1);
            cascadePrediction=new MotionPrediction(rate,order>=2?MotionPrediction.limitNorm(acceleration,100):new double[3],
                    order>=3?MotionPrediction.limitNorm(jerk,2000):new double[3],order,0.002);
            prediction=norm(current)<0.01 || dot(current,rate)<=0 || norm(sub(rate,current))>Math.max(0.15,0.5*norm(current))
                    ?MotionPrediction.linear(current):cascadePrediction;
        }
        private static double alpha(double frequency,double dt) { double x=2*Math.PI*frequency*dt; return x/(1+x); }
    }

    // ---- Extracted from QuietAnchorPredictionFilter.java ----
    /** V2a host quiet-motion policy; smooths prediction lead, never integrated pose or bias. */
    static final class QuietAnchorPredictionFilter {
        private final AnchorPredictionFilter cascade=new AnchorPredictionFilter();
        private long previousTime=-1;
        private double[] quietRate=new double[3];
        MotionPrediction prediction=MotionPrediction.NONE;
        double derivativeConfidence;
        void reset() { cascade.reset(); previousTime=-1; quietRate=new double[3]; prediction=MotionPrediction.NONE; derivativeConfidence=0; }
        private static double smoothstep(double low,double high,double value) {
            double x=Math.max(0,Math.min(1,(value-low)/(high-low))); return x*x*(3-2*x);
        }
        void accept(long time,long frame,double[] current,double[] bias) {
            if(!finite(current) || !finite(bias)) { reset(); return; }
            if(previousTime>=0 && time<=previousTime) return;
            double dt=previousTime>=0?(time-previousTime)*1e-9:0;
            cascade.accept(time,frame,current,bias); previousTime=time;
            MotionPrediction c=cascade.cascadePrediction;
            if(c.order==0) {
                quietRate=current.clone(); derivativeConfidence=0; prediction=MotionPrediction.linear(current,0,0); return;
            }
            quietRate=add(quietRate,mul(sub(current,quietRate),1-Math.exp(-2*Math.PI*12*dt)));
            // Acceleration prevents quiet-path lag as an intentional turn crosses zero speed.
            double activity=Math.max(norm(quietRate),0.02*norm(c.acceleration()));
            double motion=smoothstep(0.015,0.08,activity),speed=norm(current);
            double disagreement=norm(sub(current,c.rate()))/Math.max(0.02,0.5*speed);
            double alignment=dot(current,c.rate())/Math.max(1e-12,speed*norm(c.rate()));
            double trust=(1-smoothstep(0.25,1,disagreement))*smoothstep(-0.1,0.8,alignment);
            derivativeConfidence=motion*trust;
            double[] filtered=add(quietRate,mul(sub(c.rate(),quietRate),motion));
            prediction=new MotionPrediction(add(filtered,mul(sub(current,filtered),motion*(1-trust))),
                    mul(c.acceleration(),derivativeConfidence),mul(c.jerk(),0.5*derivativeConfidence*derivativeConfidence),c.order,0);
        }
    }

    // ---- Extracted from PredictionModel.java ----
    enum PredictionModel {
        V1("constantRateV1", "V1 · Constant rate"),
        V2("anchorV2", "V2 · Anchor"),
        V2A("quietAnchorV2a", "V2a · Quiet motion");
        static final PredictionModel DEFAULT = V2A;
        final String id, title;
        PredictionModel(String id,String title) { this.id=id; this.title=title; }
        static PredictionModel fromId(String id) {
            for(PredictionModel model:values()) if(model.id.equals(id)) return model;
            return DEFAULT;
        }
    }

    // ---- Extracted from AnchoredScreen.java ----
    /** A fixed 3840x2160 plane viewed by a rotating pinhole camera (3DoF).
     * Camera axes: right, down, forward. Positive sensor yaw looks right, pitch looks up.
     * At recenter, source pixels map 1:1 to output pixels. No edge clamping or billboarding. */
    static final class AnchoredScreen {
        private final int sourceWidth,sourceHeight;
        final float[] matrix = new float[9];
        final float[] clipPolygon;
        final float sourceLeft, sourceTop, sourceRight, sourceBottom;
        private final double[] h = new double[9];
        private final double[] depth;
        private static final double NEAR = 0.01;

        AnchoredScreen(int width, int height, double horizontalFovDegrees, double[] orientation) {
            this(width, height, horizontalFovDegrees, 1, orientation);
        }

        AnchoredScreen(int width, int height, double horizontalFovDegrees, double distance, double[] orientation) {
            this(width,height,3840,2160,horizontalFovDegrees,distance,orientation);
        }
        AnchoredScreen(int width,int height,int sourceWidth,int sourceHeight,double horizontalFovDegrees,double distance,double[] orientation) {
            this.sourceWidth=sourceWidth; this.sourceHeight=sourceHeight;
            if (!Double.isFinite(distance) || distance < 0.25 || distance > 4) throw new IllegalArgumentException("Invalid distance");
            if (width <= 0 || height <= 0 || horizontalFovDegrees < 20 || horizontalFovDegrees > 100)
                throw new IllegalArgumentException("Invalid projection");
            double f = width / (2 * Math.tan(Math.toRadians(horizontalFovDegrees) / 2));
            // Inverse head orientation maps the fixed world plane into the current camera frame.
            double w = orientation[0], x = -orientation[1], y = -orientation[2], z = -orientation[3];
            double norm = Math.sqrt(w*w+x*x+y*y+z*z);
            if (!Double.isFinite(norm) || norm < 1e-9) throw new IllegalArgumentException("Invalid quaternion");
            w/=norm; x/=norm; y/=norm; z/=norm;
            double[] rx = {1-2*(y*y+z*z), 2*(x*y-w*z), 2*(x*z+w*y)};
            double[] ry = {2*(x*y+w*z), 1-2*(x*x+z*z), 2*(y*z-w*x)};
            double[] rz = {2*(x*z-w*y), 2*(y*z+w*x), 1-2*(x*x+y*y)};
            double cx = sourceWidth/2.0, cy = sourceHeight/2.0;
            double[] cameraX = {rx[0]/f, rx[1]/f, rx[2]*distance - (rx[0]*cx+rx[1]*cy)/f};
            double[] cameraY = {ry[0]/f, ry[1]/f, ry[2]*distance - (ry[0]*cx+ry[1]*cy)/f};
            depth = new double[]{rz[0]/f, rz[1]/f, rz[2]*distance - (rz[0]*cx+rz[1]*cy)/f};
            for (int i=0; i<3; i++) {
                h[i] = f*cameraX[i] + width/2.0*depth[i];
                h[3+i] = f*cameraY[i] + height/2.0*depth[i];
                h[6+i] = depth[i];
            }
            for (int i=0; i<9; i++) matrix[i]=(float)h[i];
            List<double[]> polygon = new ArrayList<>();
            polygon.add(new double[]{0,0}); polygon.add(new double[]{sourceWidth,0});
            polygon.add(new double[]{sourceWidth,sourceHeight}); polygon.add(new double[]{0,sourceHeight});
            // Clip in source space before division. Also handles looking away and crossing the near plane.
            polygon = clip(polygon, new double[]{depth[0],depth[1],depth[2]-NEAR});
            polygon = clip(polygon, new double[]{h[0],h[1],h[2]});
            polygon = clip(polygon, new double[]{width*depth[0]-h[0],width*depth[1]-h[1],width*depth[2]-h[2]});
            polygon = clip(polygon, new double[]{h[3],h[4],h[5]});
            polygon = clip(polygon, new double[]{height*depth[0]-h[3],height*depth[1]-h[4],height*depth[2]-h[5]});
            clipPolygon = new float[polygon.size()*2];
            double left=sourceWidth, top=sourceHeight, right=0, bottom=0;
            for (int i=0;i<polygon.size();i++) {
                double[] p=polygon.get(i);
                left=Math.min(left,p[0]); top=Math.min(top,p[1]); right=Math.max(right,p[0]); bottom=Math.max(bottom,p[1]);
                double d=eval(depth,p);
                clipPolygon[2*i]=(float)((h[0]*p[0]+h[1]*p[1]+h[2])/d);
                clipPolygon[2*i+1]=(float)((h[3]*p[0]+h[4]*p[1]+h[5])/d);
            }
            sourceLeft=polygon.isEmpty()?0:(float)left; sourceTop=polygon.isEmpty()?0:(float)top;
            sourceRight=(float)right; sourceBottom=(float)bottom;
        }
        boolean fullyInFront() {
            return depth[2]+Math.min(0,depth[0]*sourceWidth)+Math.min(0,depth[1]*sourceHeight)>0.05;
        }

        /** Allocation-free homography for a single scanout strip; same projection as the clipped plane. */
        static void matrixFor(int width, int height, double fov, double distance, double[] q, float[] out) {
            matrixFor(width,height,3840,2160,fov,distance,q,out);
        }
        static void matrixFor(int width,int height,int sourceWidth,int sourceHeight,double fov,double distance,double[] q,float[] out) {
            double f=width/(2*Math.tan(Math.toRadians(fov)/2));
            double w=q[0], x=-q[1], y=-q[2], z=-q[3];
            double norm=Math.sqrt(w*w+x*x+y*y+z*z); w/=norm; x/=norm; y/=norm; z/=norm;
            double r00=1-2*(y*y+z*z), r01=2*(x*y-w*z), r02=2*(x*z+w*y);
            double r10=2*(x*y+w*z), r11=1-2*(x*x+z*z), r12=2*(y*z-w*x);
            double r20=2*(x*z-w*y), r21=2*(y*z+w*x), r22=1-2*(x*x+y*y);
            double cx=sourceWidth/2.0, cy=sourceHeight/2.0;
            double dx=r20/f, dy=r21/f, dz=r22*distance-(r20*cx+r21*cy)/f;
            out[0]=(float)(r00+width/2.0*dx); out[1]=(float)(r01+width/2.0*dy);
            out[2]=(float)(f*r02*distance-r00*cx-r01*cy+width/2.0*dz);
            out[3]=(float)(r10+height/2.0*dx); out[4]=(float)(r11+height/2.0*dy);
            out[5]=(float)(f*r12*distance-r10*cx-r11*cy+height/2.0*dz);
            out[6]=(float)dx; out[7]=(float)dy; out[8]=(float)dz;
        }

        boolean visible() { return clipPolygon.length >= 6; }
        float[] project(float sourceX, float sourceY) {
            double d=depth[0]*sourceX+depth[1]*sourceY+depth[2];
            if (d < NEAR) return null;
            return new float[]{(float)((h[0]*sourceX+h[1]*sourceY+h[2])/d),
                    (float)((h[3]*sourceX+h[4]*sourceY+h[5])/d)};
        }
        private static double eval(double[] plane, double[] p) { return plane[0]*p[0]+plane[1]*p[1]+plane[2]; }
        private static List<double[]> clip(List<double[]> input, double[] plane) {
            List<double[]> output=new ArrayList<>();
            if (input.isEmpty()) return output;
            double[] previous=input.get(input.size()-1);
            double a=eval(plane,previous);
            for (double[] point: input) {
                double b=eval(plane,point);
                if ((a>=0)!=(b>=0)) {
                    double t=a/(a-b);
                    output.add(new double[]{previous[0]+t*(point[0]-previous[0]),previous[1]+t*(point[1]-previous[1])});
                }
                if(b>=0) output.add(point);
                previous=point; a=b;
            }
            return output;
        }
    }

    // ---- Extracted from CalibrationHud.java ----
    /** Screen-space content: reprojected with the source, not a face-locked Android overlay. */
    static final class CalibrationHud {
        record State(float progress,int phase,float animation,boolean waiting) {
            static final State HIDDEN=new State(0,0,0,false);
            static State forTracking(boolean anchored,boolean frozen,boolean fresh,boolean biasReady,
                    boolean calibrated,int progress,boolean waiting,long nowNanos) {
                if(!anchored || frozen || fresh && calibrated) return HIDDEN;
                return new State(Math.max(0,Math.min(100,progress))/100f,!fresh?3:!biasReady?1:2,
                        (nowNanos%2_000_000_000L)/2_000_000_000f,waiting);
            }
            boolean spinning() { return phase==3 || waiting; }
        }
        private static final String[][] LABELS={{"Learning gyro bias","Look at one fixed point"},
                {"Tracking active","Refining calibration"},{"Waiting for IMU","Motion data unavailable"}};
        static Bitmap makeLabels() {
            Bitmap bitmap=Bitmap.createBitmap(256,192,Bitmap.Config.ARGB_8888);
            bitmap.setDensity(Bitmap.DENSITY_NONE);
            Canvas canvas=new Canvas(bitmap); Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER);
            for(int row=0;row<3;row++) for(int line=0;line<2;line++) {
                paint.setTypeface(Typeface.create("sans-serif",line==0?Typeface.BOLD:Typeface.NORMAL));
                paint.setTextSize(line==0?21:16);
                canvas.drawText(LABELS[row][line],128,row*64+24+line*25,paint);
            }
            return bitmap;
        }
        // Shared AGSL/GLSL body keeps both single-pass renderers visually identical.
        private static final String BODY="""
            float4 calibrationCard(float4 color, float2 uv) {
                if(calibration.y==0.0) return color;
                float scale=max(0.35,min(sourceSize.x/3840.0,sourceSize.y/2160.0));
                float2 p=(uv-sourceSize*0.5)/scale;
                float2 box=abs(p-float2(0.0,30.0))-float2(134.0,66.0);
                float edge=length(max(box,float2(0.0)))+min(max(box.x,box.y),0.0)-14.0;
                if(edge>1.0) return color;
                color.rgb=mix(color.rgb,float3(0.025,0.035,0.05),0.9*(1.0-smoothstep(-1.0,1.0,edge)));
                float radial=length(p);
                float stroke=1.0-smoothstep(2.0,3.5,abs(radial-27.0));
                float phase=fract(ATAN(p.x,-p.y)/6.2831853+1.0);
                bool stale=calibration.y>2.5;
                float arc=(stale || calibration.w>0.5)
                    ? (fract(phase-calibration.z+1.0)<0.18?1.0:0.0) : (phase<calibration.x?1.0:0.0);
                float3 tint=stale?float3(0.75,0.78,0.85):calibration.y>1.5?float3(0.15,0.88,0.82):float3(1.0,0.68,0.18);
                color.rgb=mix(color.rgb,mix(float3(0.14,0.18,0.23),tint,arc),stroke);
                float center=(1.0-smoothstep(2.5,3.5,radial))*(0.5+0.2*sin(calibration.z*6.2831853));
                color.rgb=mix(color.rgb,tint,center);
                float2 text=p-float2(-128.0,40.0);
                if(text.x>=0.0 && text.y>=0.0 && text.x<256.0 && text.y<64.0) {
                    float2 labelPixel=text+float2(0.0,(calibration.y-1.0)*64.0);
                    float alpha=LABEL_ALPHA;
                    color.rgb=mix(color.rgb,float3(0.94,0.96,1.0),alpha);
                }
                return color;
            }
            """;
        static String agsl() { return BODY.replace("ATAN","atan").replace("LABEL_ALPHA","labels.eval(labelPixel).a"); }
        static String glsl() {
            return BODY.replace("float4","vec4").replace("float3","vec3").replace("float2","vec2")
                    .replace("ATAN","atan").replace("LABEL_ALPHA","texture(labels,labelPixel/vec2(256.0,192.0)).a");
        }
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Bitmap labels;
        void draw(Canvas canvas,State state,int sourceWidth,int sourceHeight) {
            if(state.phase==0) return;
            if(labels==null) labels=makeLabels();
            canvas.save();
            canvas.translate(sourceWidth/2f,sourceHeight/2f);
            float scale=Math.max(0.35f,Math.min(sourceWidth/3840f,sourceHeight/2160f));
            canvas.scale(scale,scale);
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xe606090d);
            canvas.drawRoundRect(-148,-50,148,110,14,14,paint);
            int tint=state.phase==3?0xffbfc7d9:state.phase==2?0xff26e0d1:0xffffad2e;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5.5f); paint.setColor(0xff242e3b);
            canvas.drawCircle(0,0,27,paint); paint.setColor(tint);
            canvas.drawArc(-27,-27,27,27,-90+(state.spinning()?state.animation*360:0),
                    state.spinning()?64.8f:state.progress*360,false,paint);
            paint.setStyle(Paint.Style.FILL); paint.setAlpha((int)(255*(0.5+0.2*Math.sin(state.animation*2*Math.PI))));
            canvas.drawCircle(0,0,3,paint); paint.setColor(Color.WHITE);
            canvas.save(); canvas.clipRect(-128,40,128,104);
            canvas.drawBitmap(labels,-128,40-(state.phase-1)*64,paint);
            canvas.restore(); canvas.restore();
        }
        void release() { labels=null; }
    }

    // ---- Extracted from PlaneShader.java ----
    /** One full-surface GPU draw. Inverse ray/plane mapping also handles the horizon and plane edges.
     * Owned exclusively by XRRender. No intermediate warped bitmap or CPU pixel processing. */
    @TargetApi(33)
    static final class PlaneShader {
        private static final String PROGRAM = """
                uniform shader source;
                uniform shader labels;
                uniform float4 calibration;
                uniform float2 viewport;
                uniform float2 sourceSize;
                uniform float focal;
                uniform float planeDistance;
                uniform float4 orientation;
                uniform float4 motionRate;   // xyz rad/s, w center seconds
                uniform float4 motionAccel;  // xyz rad/s², w derivative allowance floor
                uniform float4 motionJerk;   // xyz rad/s³, w signed scanout seconds

                """ + CalibrationHud.agsl() + """
                float4 multiplyQuaternion(float4 a, float4 b) {
                    return float4(a.w*b.xyz + b.w*a.xyz + cross(a.xyz, b.xyz),
                                  a.w*b.w - dot(a.xyz, b.xyz));
                }
                float3 rotateRay(float4 q, float3 v) {
                    return v + 2.0*cross(q.xyz, cross(q.xyz, v) + q.w*v);
                }
                half4 main(float2 pixel) {
                    // Canvas/AGSL coordinates start at the top left. Quaternion uniform is xyzw.
                    float4 q = orientation;
                    if (motionJerk.w != 0.0) {
                        float t = clamp(motionRate.w + (pixel.y/viewport.y-0.5)*motionJerk.w, -0.03, 0.05);
                        float3 linear = motionRate.xyz*t;
                        float3 extra = motionAccel.xyz*(t*t/2.0) + motionJerk.xyz*(t*t*t/6.0);
                        float bound = min(0.035, 0.5*length(linear)+motionAccel.w);
                        extra *= min(1.0, bound/max(1e-15,length(extra)));
                        float3 r = linear+extra;
                        float angle = length(r);
                        if (angle > 1e-9) q = normalize(multiplyQuaternion(q, float4(r/angle*sin(angle/2.0),cos(angle/2.0))));
                    }
                    float3 ray = rotateRay(q, float3((pixel - viewport*0.5)/focal, 1.0));
                    // Ray in world space intersects the fixed z=distance plane. Its parameter is
                    // also camera-space depth, since the original camera ray has z=1.
                    if (ray.z <= 0.000001) return half4(0.0, 0.0, 0.0, 1.0);
                    float depth = planeDistance / ray.z;
                    if (depth < 0.01) return half4(0.0, 0.0, 0.0, 1.0);
                    float2 uv = sourceSize*0.5 + focal*depth*ray.xy;
                    if (uv.x < 0.0 || uv.y < 0.0 || uv.x >= sourceSize.x || uv.y >= sourceSize.y)
                        return half4(0.0, 0.0, 0.0, 1.0);
                    return half4(calibrationCard(float4(source.eval(uv)),uv));
                }
                """;

        private final RuntimeShader shader = new RuntimeShader(PROGRAM);
        private final Paint paint = new Paint();

        private final double[] centerPose=new double[4];

        void calibration(CalibrationHud.State state) {
            shader.setFloatUniform("calibration",state.progress(),state.phase(),state.animation(),state.waiting()?1:0);
        }

        PlaneShader(Bitmap source) {
            BitmapShader labels=new BitmapShader(CalibrationHud.makeLabels(),Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            labels.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shader.setInputShader("labels",labels);
            calibration(CalibrationHud.State.HIDDEN);
            BitmapShader texture = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            // RuntimeShader child sampling otherwise defaults to nearest, independent of Paint.
            texture.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shader.setInputShader("source", texture);
            shader.setFloatUniform("sourceSize", source.getWidth(), source.getHeight());
            paint.setShader(shader);
        }

        void draw(Canvas canvas, int width, int height, double fov, double distance,
                double[] q, double[] velocity, double scanoutMs) {
            draw(canvas,width,height,fov,distance,q,MotionPrediction.linear(velocity),0,scanoutMs);
        }

        void draw(Canvas canvas, int width, int height, double fov, double distance,
                double[] q, MotionPrediction motion, double centerSeconds, double scanoutMs) {
            shader.setFloatUniform("viewport", width, height);
            shader.setFloatUniform("focal", (float)(width/(2*Math.tan(Math.toRadians(fov)/2))));
            shader.setFloatUniform("planeDistance", (float)distance);
            // No row-dependent prediction: evaluate once per draw, not once per fragment.
            if(scanoutMs==0 && centerSeconds!=0 && motion!=MotionPrediction.NONE) { motion.predict(q,centerSeconds,centerPose); q=centerPose; }
            double norm = Math.sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]);
            shader.setFloatUniform("orientation", (float)(q[1]/norm), (float)(q[2]/norm),
                    (float)(q[3]/norm), (float)(q[0]/norm));
            shader.setFloatUniform("motionRate", (float)motion.rx,(float)motion.ry,(float)motion.rz,(float)centerSeconds);
            shader.setFloatUniform("motionAccel", (float)motion.ax,(float)motion.ay,(float)motion.az,(float)motion.derivativeLimitFloor);
            shader.setFloatUniform("motionJerk", (float)motion.jx,(float)motion.jy,(float)motion.jz,(float)(scanoutMs/1000));
            canvas.drawRect(0, 0, width, height, paint);
        }
    }
}
