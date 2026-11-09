package com.carriez.flutter_hbb.xr;

final class XrRenderer {
    static { System.loadLibrary("rustdesk_xr"); }
    static native void enable(boolean enabled);
    static native boolean pending();
    static native boolean initialize();
    static native boolean draw(int width, int height, boolean fit, float panX, float panY);
    static native int[] prepareProjection();
    /** Time spent uploading frames to the GPU since last asked, in microseconds; 0 if none. */
    static native long takeUploadMicros();
    private XrRenderer() {}
}
