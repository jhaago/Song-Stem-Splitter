package com.jhaago.vocalreducer;

/** The one gain and PCM conversion policy used by playback and saved WAV files. */
public final class MixMath {
    private MixMath() {}

    public static float gain(int vocalLevel) {
        if (vocalLevel < 0 || vocalLevel > 100) {
            throw new IllegalArgumentException("Vocal level must be between 0 and 100.");
        }
        return vocalLevel / 100f;
    }

    public static float mix(float original, float instrumental, float gain) {
        return instrumental * (1f - gain) + original * gain;
    }

    public static short pcm16(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("The audio contains an invalid sample.");
        return (short) Math.max(-32768, Math.min(32767, Math.round(value * 32768f)));
    }
}
