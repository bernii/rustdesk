package com.carriez.flutter_hbb.xr;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Matrix;
import android.opengl.*;
import android.os.Bundle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Exercises the shipped shader against the probe's independent homography oracle. No IMU/profile writes. */
public final class XrProjectionInstrumentation extends Instrumentation {
    private static final int W = 1920, H = 1200;
    private int program, samples, cases;
    private static boolean sharp;
    private boolean checkerStress;
    private EGLDisplay display;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        checkerStress = arguments != null && "true".equals(arguments.getString("checker"));
        sharp = arguments != null && "true".equals(arguments.getString("sharp"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            checkDictationPreview();
            runChecks();
            result.putString("stream", "\nPASS: " + cases + " GPU projection cases, " + samples
                    + " pixels; BGRA, 1:1 coordinates, rotations, edges, HUD and cursor; 2 dictation overlay layouts.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (display != null) { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); EGL14.eglTerminate(display); }
        }
    }
    private void checkDictationPreview() {
        Activity activity=startActivitySync(new android.content.Intent(getTargetContext(),
                com.carriez.flutter_hbb.MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        final Throwable[] failure=new Throwable[1];
        runOnMainSync(() -> {
            try {
                XrController owner=XrController.current.get();
                check(owner!=null,"XR controller attached");
                java.util.Map<String,Object> saved=new java.util.HashMap<>(owner.overlay);
                try {
                    owner.overlay.put("anchored",true); owner.overlay.put("dictating",true);
                    owner.overlay.put("dictationStopping",true);
                    owner.overlay.put("transcript","A long dictated message stays visible near the middle of the glasses. "
                            + "Release to send it, pinch inward to send Enter, or pinch outward to discard the preview. "
                            + "This last sentence also needs to remain visible.");
                    for(int[] size:new int[][]{{1920,1200},{2560,1080}}) {
                        XrOverlay overlay=new XrOverlay(activity,owner);
                        overlay.measure(android.view.View.MeasureSpec.makeMeasureSpec(size[0],android.view.View.MeasureSpec.EXACTLY),
                                android.view.View.MeasureSpec.makeMeasureSpec(size[1],android.view.View.MeasureSpec.EXACTLY));
                        overlay.layout(0,0,size[0],size[1]);
                        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(size[0],size[1],android.graphics.Bitmap.Config.ARGB_8888);
                        overlay.draw(new android.graphics.Canvas(image));
                        int central=0,bottom=0;
                        for(int y=size[1]/4;y<size[1];y++) for(int x=size[0]/8;x<size[0]*7/8;x++) {
                            int color=image.getPixel(x,y);
                            if(android.graphics.Color.alpha(color)>200 && android.graphics.Color.red(color)>220
                                    && android.graphics.Color.green(color)>220 && android.graphics.Color.blue(color)>220) {
                                if(y<size[1]*.85) central++; else bottom++;
                            }
                        }
                        check(central>1000 && bottom==0,"dictation stays in central visible area "+size[0]+"x"+size[1]);
                        try(java.io.FileOutputStream out=new java.io.FileOutputStream(
                                new java.io.File(getTargetContext().getFilesDir(),"dictation-preview-"+size[0]+".png"))) {
                            image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
                        }
                        image.recycle();
                    }
                } finally { owner.overlay.clear(); owner.overlay.putAll(saved); }
            } catch(Throwable error) { failure[0]=error; }
            finally { activity.finish(); }
        });
        if(failure[0]!=null) throw new AssertionError("Dictation overlay",failure[0]);
    }
    private void runChecks() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        check(EGL14.eglInitialize(display, new int[2], 0, new int[2], 0), "EGL initialize");
        EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
        EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_RED_SIZE,8,
                EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,count,0);
        check(count[0] > 0, "EGL config");
        EGLContext context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display,configs[0],
                new int[]{EGL14.EGL_WIDTH,W,EGL14.EGL_HEIGHT,H,EGL14.EGL_NONE},0);
        check(EGL14.eglMakeCurrent(display,surface,surface,context), "EGL current");
        new XrProjectionRenderer().initialize();
        int[] current = new int[1]; GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM,current,0); program = current[0];
        FloatBuffer vertices=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(new float[]{-1,-1,3,-1,-1,3}).position(0);
        GLES20.glEnableVertexAttribArray(0); GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,0,vertices);
        GLES20.glViewport(0,0,W,H);
        GLES20.glUniform2f(u("viewport"),W,H);
        GLES20.glUniform1f(u("focal"),(float)(W/(2*Math.tan(Math.toRadians(XrProjectionRenderer.FOV)/2))));
        GLES20.glUniform1f(u("planeDistance"),1);
        int[] textures=new int[2]; GLES20.glGenTextures(2,textures,0);
        ByteBuffer green=ByteBuffer.allocateDirect(4); green.put(new byte[]{0,(byte)255,0,(byte)255}).position(0);
        bind(textures[1],GLES20.GL_TEXTURE2,1,1,green);
        double[][] poses={{1,0,0,0},rotation(0,1,0,20),rotation(1,0,0,15),rotation(0,0,1,25),
                rotation(1,2,3,25),rotation(0,1,0,80),rotation(0,1,0,180)};
        for(int[] size:new int[][]{{3840,2160},{1280,720}}) {
            int sw=size[0],sh=size[1]; ByteBuffer pixels=ByteBuffer.allocateDirect(sw*sh*4);
            for(int y=0;y<sh;y++) for(int x=0;x<sw;x++) pixels.put((byte)value(x,y,2)).put((byte)value(x,y,1)).put((byte)value(x,y,0)).put((byte)255);
            pixels.position(0); bind(textures[0],GLES20.GL_TEXTURE0,sw,sh,pixels);
            GLES20.glUniform2f(u("sourceSize"),sw,sh);
            for(double[] q:poses) {
                pose(q); GLES20.glUniform4f(u("calibration"),0,0,0,0); GLES20.glUniform4f(u("cursorRect"),0,0,0,0);
                ByteBuffer rendered=render();
                Xreal3Dof.Projection p=new Xreal3Dof.Projection(W,H,sw,sh,45,1,q);
                Matrix forward=new Matrix(),inverse=new Matrix(); forward.setValues(p.homography());
                check(forward.invert(inverse),"invert projection");
                for(int y=7;y<H;y+=19) for(int x=11;x<W;x+=23) {
                    float[] point={x+.5f,y+.5f}; inverse.mapPoints(point);
                    boolean inside=p.visible() && point[0]>=0 && point[0]<sw && point[1]>=0 && point[1]<sh && p.project(point[0],point[1])!=null;
                    // Avoid the discontinuity at the exact source boundary, not interior grid edges.
                    if(Math.abs(point[0])<.01 || Math.abs(point[1])<.01 || Math.abs(point[0]-sw)<.01 || Math.abs(point[1]-sh)<.01) continue;
                    for(int c=0;c<3;c++) {
                        int expected=inside?bilinear(point[0]-.5,point[1]-.5,sw,sh,c):0;
                        int actual=rendered.get(((H-1-y)*W+x)*4+c)&255;
                        check(Math.abs(expected-actual)<=2,"pixel "+x+","+y+" channel "+c+" expected "+expected+" got "+actual+" case "+cases);
                    }
                    samples++;
                }
                cases++;
            }
            if(sw==3840) {
                pose(poses[0]); GLES20.glUniform4f(u("calibration"),0,0,0,0);
                ByteBuffer plain=render();
                for(int phase=1;phase<=3;phase++) {
                    GLES20.glUniform4f(u("calibration"),.75f,phase,.25f,1);
                    ByteBuffer hud=render(); int changed=0;
                    for(int y=H/2-50;y<H/2+110;y++) for(int x=W/2-148;x<W/2+148;x++) {
                        int i=((H-1-y)*W+x)*4;
                        if(hud.get(i)!=plain.get(i) || hud.get(i+1)!=plain.get(i+1)) changed++;
                    }
                    check(changed>10000,"visible calibration phase "+phase); cases++;
                }
                GLES20.glUniform4f(u("calibration"),0,0,0,0);
                GLES20.glUniform4f(u("cursorRect"),sw/2f-10,sh/2f-10,20,20);
                ByteBuffer cursor=render(); int i=((H-1-H/2)*W+W/2)*4;
                check((cursor.get(i)&255)==0 && (cursor.get(i+1)&255)==255 && (cursor.get(i+2)&255)==0,"projected cursor color"); cases++;
            }
        }
        if (!checkerStress) {
            EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context);
            return;
        }
        // Optional strict filtering stress case, retained separately from geometry/HUD checks.
        // Exact centered 1:1 must retain a one-pixel checkerboard, including texture orientation.
        int sw=3840,sh=2160; ByteBuffer checker=ByteBuffer.allocateDirect(sw*sh*4);
        for(int y=0;y<sh;y++) for(int x=0;x<sw;x++) {
            byte v=(byte)(((x+y)%2)*255); checker.put(v).put(v).put(v).put((byte)255);
        }
        checker.position(0); bind(textures[0],GLES20.GL_TEXTURE0,sw,sh,checker);
        GLES20.glUniform2f(u("sourceSize"),sw,sh); pose(poses[0]);
        GLES20.glUniform4f(u("cursorRect"),0,0,0,0); GLES20.glUniform4f(u("calibration"),0,0,0,0);
        ByteBuffer identity=render();
        // Compare the same checkerboard through the unchanged native flat shader as a baseline.
        check(XrRenderer.initialize(), "native baseline initialization");
        bind(textures[0], GLES20.GL_TEXTURE0, sw, sh, checker);
        float sx=(float)sw/W,sy=(float)sh/H;
        FloatBuffer flat=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        flat.put(new float[]{-sx,-sy,0,1, sx,-sy,1,1, -sx,sy,0,0, sx,sy,1,0}).position(0);
        GLES20.glEnableVertexAttribArray(1); GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,16,flat);
        flat.position(2); GLES20.glVertexAttribPointer(1,2,GLES20.GL_FLOAT,false,16,flat);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        ByteBuffer baseline=ByteBuffer.allocateDirect(W*H*4);
        GLES20.glReadPixels(0,0,W,H,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,baseline);
        for(int y=3;y<H;y+=17) for(int x=3;x<W;x+=19) {
            int expected=((x+(sw-W)/2+y+(sh-H)/2)%2)*255;
            for(int c=0;c<3;c++) check(Math.abs((identity.get(((H-1-y)*W+x)*4+c)&255)-expected)<=1,"1:1 checkerboard at "+x+","+y+" expected "+expected+" got "+(identity.get(((H-1-y)*W+x)*4+c)&255)+"; unchanged flat shader got "+(baseline.get(((H-1-y)*W+x)*4+c)&255));
            samples++;
        }
        cases++;
        EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context);
    }
    private int u(String name) { return GLES20.glGetUniformLocation(program,name); }
    private void pose(double[] q) { GLES20.glUniform4f(u("orientation"),(float)q[1],(float)q[2],(float)q[3],(float)q[0]); }
    private static double[] rotation(double x,double y,double z,double degrees) {
        double half=Math.toRadians(degrees)/2,scale=Math.sin(half)/Math.sqrt(x*x+y*y+z*z);
        return new double[]{Math.cos(half),x*scale,y*scale,z*scale};
    }
    private ByteBuffer render() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,3);
        ByteBuffer bytes=ByteBuffer.allocateDirect(W*H*4);
        GLES20.glReadPixels(0,0,W,H,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes);
        check(GLES20.glGetError()==GLES20.GL_NO_ERROR,"GL render"); return bytes;
    }
    private static void bind(int texture,int unit,int w,int h,ByteBuffer bytes) {
        GLES20.glActiveTexture(unit); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes);
    }
    private static int value(int x,int y,int c) {
        if (sharp) return c==0?x%251:c==1?y%241:(x/31+y/17)%239;
        // Continuous ramps isolate geometry from platform-dependent filtering at wrap discontinuities.
        int n=c==0?x:c==1?y:x/3+y/2;
        return 250-Math.abs(n%500-250);
    }
    private static int bilinear(double x,double y,int w,int h,int c) {
        x=Math.max(0,Math.min(w-1,x)); y=Math.max(0,Math.min(h-1,y));
        int a=(int)x,b=(int)y,ax=Math.min(w-1,a+1),by=Math.min(h-1,b+1);
        double dx=x-a,dy=y-b;
        return (int)Math.round((value(a,b,c)*(1-dx)+value(ax,b,c)*dx)*(1-dy)+(value(a,by,c)*(1-dx)+value(ax,by,c)*dx)*dy);
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
