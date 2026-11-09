package com.carriez.flutter_hbb.xr;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;

/** Live BGRA texture consumer; the tracker and source producer have independent lifetimes. */
final class XrProjectionRenderer {
    static final double FOV = 45, DISTANCE = 1;
    // Android's retained 8 ms optical lead plus one 60 Hz refresh: estimates, not measured photon timing.
    private static final double PREDICTION_LEAD_SECONDS = 0.008 + 1.0 / 60;
    private static final String VERTEX = "attribute vec2 position; void main(){gl_Position=vec4(position,0.,1.);}";
    private static final String FRAGMENT = """
        precision highp float;
        uniform sampler2D source, labels, cursor;
        uniform vec2 viewport, sourceSize;
        uniform vec4 orientation, calibration, cursorRect;
        uniform float focal, planeDistance, curvature;
        """ + Xreal3Dof.CalibrationHud.glsl().replace("texture(", "texture2D(") + """
        // Source pixel (unbounded) seen through a display pixel; false when the ray misses the screen.
        bool sourcePoint(vec2 pixel, out vec2 uv) {
            vec3 v=vec3((pixel-viewport*0.5)/focal,1.0);
            vec3 ray=v+2.0*cross(orientation.xyz,cross(orientation.xyz,v)+orientation.w*v);
            vec2 surface;
            uv=vec2(0.0);
            if(curvature<=0.0) {
                if(ray.z<=0.000001) return false;
                float depth=planeDistance/ray.z;
                if(depth<0.01) return false;
                surface=depth*ray.xy;
            } else {
                // Mac XR's cylinder: radius planeDistance/curvature, center kept at (0,0,planeDistance).
                // Solved with the inverse radius so nearly flat values don't subtract huge radii.
                float k=curvature/planeDistance, a=dot(ray.xz,ray.xz);
                if(a<0.000000000001) return false;
                float b=(1.0-curvature)*ray.z;
                float root=sqrt(b*b+curvature*(2.0-curvature)*a);
                float depth=b>=0.0?planeDistance*(2.0-curvature)/(root+b):(root-b)/(k*a);
                float angle=atan(k*depth*ray.x,1.0-curvature+k*depth*ray.z);
                surface=vec2(angle/k,depth*ray.y);
            }
            uv=sourceSize*0.5+focal*surface;
            return true;
        }
        // One bilinear tap per display pixel aliases thin lines once a display pixel covers more
        // than one source pixel (distance > 1, the curved screen's edges, head tilt). Average an
        // n x n grid over the pixel's footprint instead; at 1:1 or magnified this is the single
        // centre tap, so 1:1 stays pixel-exact. The spread reaches the full footprint by 1.1:
        // fading in more slowly left ~1.3x minification (a common distance) almost unfiltered.
        vec3 footprintColor(vec2 pixel, vec2 uv) {
            vec2 next, ux=vec2(0.0), uy=vec2(0.0);
            if(sourcePoint(pixel+vec2(1.0,0.0),next)) ux=next-uv;
            else if(sourcePoint(pixel-vec2(1.0,0.0),next)) ux=uv-next;
            if(sourcePoint(pixel+vec2(0.0,1.0),next)) uy=next-uv;
            else if(sourcePoint(pixel-vec2(0.0,1.0),next)) uy=uv-next;
            float f=max(length(ux),length(uy));
            if(f<=1.0) return texture2D(source,uv/sourceSize).bgr;
            float n=min(ceil(f),4.0), spread=min((f-1.0)*10.0,1.0);
            vec3 sum=vec3(0.0);
            for(int i=0;i<4;i++) for(int j=0;j<4;j++) {
                if(float(i)>=n || float(j)>=n) continue;
                vec2 o=((vec2(float(i),float(j))+0.5)/n-0.5)*spread;
                vec2 s=clamp(uv+o.x*ux+o.y*uy,vec2(0.5),sourceSize-0.5);
                sum+=texture2D(source,s/sourceSize).bgr;
            }
            return sum/(n*n);
        }
        void main() {
            vec2 pixel=vec2(gl_FragCoord.x,viewport.y-gl_FragCoord.y);
            gl_FragColor=vec4(0.0,0.0,0.0,1.0);
            vec2 uv;
            if(!sourcePoint(pixel,uv)) return;
            if(any(lessThan(uv,vec2(0.0))) || any(greaterThanEqual(uv,sourceSize))) return;
            vec4 color=vec4(footprintColor(pixel,uv),1.0);
            if(cursorRect.z>0.0 && cursorRect.w>0.0) {
                vec2 c=(uv-cursorRect.xy)/cursorRect.zw;
                if(all(greaterThanEqual(c,vec2(0.0))) && all(lessThan(c,vec2(1.0)))) {
                    vec4 ink=texture2D(cursor,c);
                    color.rgb=ink.rgb+color.rgb*(1.0-ink.a);
                }
            }
            gl_FragColor=calibrationCard(color,uv);
        }
        """;
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(6 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private int program, labelsTexture, cursorTexture;
    private int viewportUniform, sourceUniform, focalUniform, distanceUniform, curvatureUniform, poseUniform, calibrationUniform, cursorUniform;
    private double[] displayedPose = {1, 0, 0, 0};
    private byte[] uploadedCursor;
    private boolean cursorLoaded;
    private int cursorWidth = 24, cursorHeight = 24;
    private volatile Cursor cursor = new Cursor(null, 0, 0, 0, 0, 0, 0, false);
    private volatile float planeDistance = (float) DISTANCE, curvature;
    private volatile boolean motionPrediction;
    // Why a smooth frame rate can still look wrong: read by the same thread that draws.
    int staleFrames;
    double worstPoseAgeMs, worstHeadDegPerSecond, imuHz;

    private record Cursor(byte[] bytes, float x, float y, float hotX, float hotY,
                          float width, float height, boolean embedded) {}

    XrProjectionRenderer() { vertices.put(new float[]{-1, -1, 3, -1, -1, 3}).position(0); }

    void configure(Map<String, Object> values) {
        Object bytes = values.get("cursor");
        cursor = new Cursor(bytes instanceof byte[] ? (byte[]) bytes : null,
                number(values, "x"), number(values, "y"), number(values, "hotx"), number(values, "hoty"),
                number(values, "cursorWidth"), number(values, "cursorHeight"), Boolean.TRUE.equals(values.get("cursorEmbedded")));
        planeDistance = values.containsKey("distance") ? number(values, "distance") : (float) DISTANCE;
        curvature = number(values, "curvature");
        motionPrediction = Boolean.TRUE.equals(values.get("motionPrediction"));
    }
    private static float number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).floatValue() : 0;
    }

    void initialize() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs);
        GLES20.glBindAttribLocation(program, 0, "position");
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs);
        int[] ok = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(uniform("source"), 0);
        GLES20.glUniform1i(uniform("labels"), 1);
        GLES20.glUniform1i(uniform("cursor"), 2);
        viewportUniform = uniform("viewport"); sourceUniform = uniform("sourceSize");
        focalUniform = uniform("focal"); distanceUniform = uniform("planeDistance"); curvatureUniform = uniform("curvature");
        poseUniform = uniform("orientation"); calibrationUniform = uniform("calibration"); cursorUniform = uniform("cursorRect");
        int[] textures = new int[2]; GLES20.glGenTextures(2, textures, 0);
        labelsTexture = textures[0]; cursorTexture = textures[1]; cursorLoaded = false;
        Bitmap labels = Xreal3Dof.CalibrationHud.makeLabels();
        upload(labelsTexture, GLES20.GL_TEXTURE1, labels); labels.recycle();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }
    private int uniform(String name) { return GLES20.glGetUniformLocation(program, name); }
    private static int compile(int type, String text) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, text); GLES20.glCompileShader(shader);
        int[] ok = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }
    private static void upload(int texture, int unit, Bitmap bitmap) {
        GLES20.glActiveTexture(unit); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
    }
    private void prepareCursor(Cursor value) {
        if (cursorLoaded && uploadedCursor == value.bytes) return;
        Bitmap bitmap = value.bytes == null ? null : BitmapFactory.decodeByteArray(value.bytes, 0, value.bytes.length);
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Path arrow = new Path(); arrow.moveTo(1, 1); arrow.lineTo(9, 22);
            arrow.lineTo(12, 12); arrow.lineTo(22, 9); arrow.close();
            paint.setColor(Color.WHITE); canvas.drawPath(arrow, paint);
            paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); canvas.drawPath(arrow, paint);
        }
        cursorWidth = bitmap.getWidth(); cursorHeight = bitmap.getHeight();
        upload(cursorTexture, GLES20.GL_TEXTURE2, bitmap); bitmap.recycle();
        uploadedCursor = value.bytes; cursorLoaded = true;
    }

    boolean draw(int width, int height, Xreal3Dof tracker) {
        int[] frame = XrRenderer.prepareProjection();
        if (frame == null) return false;
        Cursor pointer = cursor;
        prepareCursor(pointer);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0, 0, 0, 1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // Read after the potentially expensive video/cursor uploads. Never predict stale data.
        Xreal3Dof.Snapshot snapshot = tracker.snapshot();
        boolean fresh = snapshot.ready();
        double poseAgeSeconds = System.nanoTime() * 1e-9 - snapshot.sampleMonotonicSeconds();
        if (!fresh) staleFrames++;
        worstPoseAgeMs = Math.max(worstPoseAgeMs, poseAgeSeconds * 1000);
        double[] rate = snapshot.angularVelocityRadPerSecond();
        worstHeadDegPerSecond = Math.max(worstHeadDegPerSecond,
                Math.toDegrees(Math.sqrt(rate[0] * rate[0] + rate[1] * rate[1] + rate[2] * rate[2])));
        imuHz = snapshot.acceptedHz();
        if (fresh) displayedPose = motionPrediction
                ? snapshot.predictedQuaternionWxyz(poseAgeSeconds + PREDICTION_LEAD_SECONDS)
                : snapshot.quaternionWxyz();
        Xreal3Dof.CalibrationHud.State hud = Xreal3Dof.CalibrationHud.State.forTracking(
                true, false, fresh, snapshot.biasReady(), snapshot.calibrated(),
                snapshot.progressPercent(), snapshot.waitingForConfidence(), System.nanoTime());
        return drawTexture(frame, width, height, displayedPose, hud, pointer);
    }

    private boolean drawTexture(int[] frame, int width, int height, double[] pose,
            Xreal3Dof.CalibrationHud.State hud, Cursor pointer) {
        if (frame[1] <= 0 || frame[2] <= 0) return true;
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame[0]);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelsTexture);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cursorTexture);
        GLES20.glUniform2f(viewportUniform, width, height); GLES20.glUniform2f(sourceUniform, frame[1], frame[2]);
        GLES20.glUniform1f(focalUniform, (float) (width / (2 * Math.tan(Math.toRadians(FOV) / 2))));
        GLES20.glUniform1f(distanceUniform, planeDistance);
        GLES20.glUniform1f(curvatureUniform, curvature);
        // CPU Hamilton WXYZ -> GLSL XYZW; signs match the probe's camera axes.
        GLES20.glUniform4f(poseUniform, (float) pose[1], (float) pose[2], (float) pose[3], (float) pose[0]);
        GLES20.glUniform4f(calibrationUniform, hud.progress(), hud.phase(), hud.animation(), hud.waiting() ? 1 : 0);
        GLES20.glUniform4f(cursorUniform, pointer.x - pointer.hotX, pointer.y - pointer.hotY,
                pointer.embedded ? 0 : pointer.width > 0 ? pointer.width : cursorWidth,
                pointer.embedded ? 0 : pointer.height > 0 ? pointer.height : cursorHeight);
        GLES20.glEnableVertexAttribArray(0); GLES20.glDisableVertexAttribArray(1);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        return GLES20.glGetError() == GLES20.GL_NO_ERROR;
    }
}
