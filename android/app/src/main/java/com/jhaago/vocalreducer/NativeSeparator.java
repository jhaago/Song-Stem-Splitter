package com.jhaago.vocalreducer;

public final class NativeSeparator {
    static { System.loadLibrary("vocal_engine"); }
    private NativeSeparator() {}
    public interface ProgressCallback { void onProgress(float fraction, String stage); }
    public static native void separate(String modelPath, String sourcePcmPath,
                                       String instrumentalPcmPath, long frameCount,
                                       ProgressCallback callback);
    public static native void cancel();
}
