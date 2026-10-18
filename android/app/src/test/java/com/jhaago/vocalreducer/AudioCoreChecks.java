package com.jhaago.vocalreducer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;

/** Runs on a plain JVM as well as through AudioCoreTest. */
public final class AudioCoreChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("Audio core: " + checks + " checks passed");
    }

    static void run() throws Exception {
        checks = 0;
        near(MixMath.mix(0.8f, 0.2f, 0f), 0.2f);
        near(MixMath.mix(0.8f, 0.2f, 1f), 0.8f);
        near(MixMath.mix(0.8f, 0.2f, 0.25f), 0.35f);
        check(MixMath.pcm16(2f) == 32767, "positive clipping");
        check(MixMath.pcm16(-2f) == -32768, "negative clipping");
        check(MixMath.pcm16(0f) == 0, "silence");
        check(MixMath.pcm16(0.5f) == 16384, "half scale");
        expect(IllegalArgumentException.class, () -> MixMath.gain(-1));
        expect(IllegalArgumentException.class, () -> MixMath.gain(101));
        expect(IllegalArgumentException.class, () -> MixMath.pcm16(Float.NaN));

        File dir = Files.createTempDirectory("vocal-audio-test").toFile();
        File source = new File(dir, "source.pcm");
        File instrumental = new File(dir, "instrumental.pcm");
        float[] x = {0.8f, -0.8f, 1.2f, -1.2f, 0f, 0.3f};
        float[] i = {0.2f, -0.2f, 0.4f, -0.4f, 0f, 0.1f};
        try {
            write(source, x);
            write(instrumental, i);
            PcmFiles.validatePair(source, instrumental, 3);
            for (int level : new int[]{0, 25, 100}) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                PcmFiles.exportWav(source, instrumental, 3, level, output, null);
                byte[] wav = output.toByteArray();
                check(wav.length == 56, "WAV length");
                check(new String(wav, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF"), "RIFF");
                check(new String(wav, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WAVE"), "WAVE");
                ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
                check(b.getInt(4) == 48, "RIFF size");
                check(b.getShort(20) == 1 && b.getShort(22) == 2, "PCM stereo");
                check(b.getInt(24) == 44100 && b.getInt(28) == 176400, "sample and byte rates");
                check(b.getShort(32) == 4 && b.getShort(34) == 16 && b.getInt(40) == 12, "WAV data header");
                for (int sample = 0; sample < x.length; sample++) {
                    short preview = MixMath.pcm16(MixMath.mix(x[sample], i[sample], MixMath.gain(level)));
                    check(b.getShort(44 + sample * 2) == preview, "preview/export parity");
                }
            }
            expect(CancellationException.class, () -> PcmFiles.exportWav(source, instrumental, 3, 25,
                    new ByteArrayOutputStream(), new PcmFiles.Progress() {
                        public boolean isCancelled() { return true; }
                        public void onProgress(float value) {}
                    }));
            expect(java.io.IOException.class, () -> PcmFiles.validatePair(source, instrumental, 4));
            write(instrumental, new float[]{0f});
            expect(java.io.IOException.class, () -> PcmFiles.validatePair(source, instrumental, 3));
            check(PcmFiles.safeName("../Hymn: \"Song\".mp3").equals(".._Hymn_ _Song_"), "filename sanitation");
            check(PcmFiles.safeName("   ").equals("Track"), "empty filename");
            check(PcmFiles.safeName("A".repeat(200)).length() == 80, "filename bounded");
        } finally {
            source.delete(); instrumental.delete(); dir.delete();
        }
    }

    private static void write(File file, float[] values) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) b.putFloat(value);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(b.array()); }
    }
    private static void near(float actual, float expected) {
        check(Math.abs(actual - expected) < 1e-6, "expected " + expected + ", got " + actual);
    }
    private static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }
    private static void expect(Class<? extends Throwable> type, Throwing action) throws Exception {
        try { action.run(); }
        catch (Throwable t) {
            check(type.isInstance(t), "expected " + type + ", got " + t);
            return;
        }
        throw new AssertionError("Expected " + type);
    }
    private interface Throwing { void run() throws Exception; }
}
