package com.jhaago.vocalreducer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;

public final class PcmFiles {
    public static final int RATE = 44100;
    public static final int CHANNELS = 2;
    public static final int BYTES_PER_FRAME = 8;
    public static final long MAX_FRAMES = RATE * 60L * 15L;
    public interface Progress {
        boolean isCancelled();
        void onProgress(float value);
    }

    private PcmFiles() {}

    public static void validatePair(File source, File instrumental, long frames) throws IOException {
        if (frames < 1 || frames > MAX_FRAMES) throw new IOException("This track must be between a moment and 15 minutes long.");
        long bytes = frames * BYTES_PER_FRAME;
        if (!source.isFile() || source.length() != bytes || !instrumental.isFile() || instrumental.length() != bytes) {
            throw new IOException("The saved audio is incomplete. Please choose and process the track again.");
        }
    }

    public static void readFloats(RandomAccessFile file, float[] values, int count, byte[] bytes) throws IOException {
        file.readFully(bytes, 0, count * 4);
        for (int n = 0; n < count; n++) {
            int k = n * 4;
            int bits = (bytes[k] & 255) | ((bytes[k + 1] & 255) << 8) |
                    ((bytes[k + 2] & 255) << 16) | ((bytes[k + 3] & 255) << 24);
            float sample = Float.intBitsToFloat(bits);
            if (!Float.isFinite(sample)) throw new IOException("The audio contains an invalid sample. Please process the track again.");
            values[n] = sample;
        }
    }

    public static void exportWav(File source, File instrumental, long frames, int level,
                                 OutputStream destination, Progress progress) throws IOException {
        validatePair(source, instrumental, frames);
        float gain = MixMath.gain(level);
        checkCancel(progress);
        BufferedOutputStream out = new BufferedOutputStream(destination, 65536);
        long dataBytes = frames * 4;
        ascii(out, "RIFF"); le32(out, dataBytes + 36); ascii(out, "WAVEfmt ");
        le32(out, 16); le16(out, 1); le16(out, 2); le32(out, RATE);
        le32(out, RATE * 4); le16(out, 4); le16(out, 16); ascii(out, "data"); le32(out, dataBytes);
        final int blockFrames = 4096;
        float[] original = new float[blockFrames * 2];
        float[] backing = new float[blockFrames * 2];
        byte[] readBuffer = new byte[blockFrames * 8];
        byte[] mixed = new byte[blockFrames * 4];
        try (RandomAccessFile x = new RandomAccessFile(source, "r");
             RandomAccessFile i = new RandomAccessFile(instrumental, "r")) {
            for (long frame = 0; frame < frames; frame += blockFrames) {
                checkCancel(progress);
                int count = (int) Math.min(blockFrames, frames - frame) * 2;
                readFloats(x, original, count, readBuffer);
                readFloats(i, backing, count, readBuffer);
                for (int s = 0; s < count; s++) {
                    short value = MixMath.pcm16(MixMath.mix(original[s], backing[s], gain));
                    mixed[s * 2] = (byte) value;
                    mixed[s * 2 + 1] = (byte) (value >> 8);
                }
                out.write(mixed, 0, count * 2);
                if (progress != null) progress.onProgress((float) (frame + count / 2) / frames);
            }
            checkCancel(progress);
            out.flush();
        }
    }

    public static String safeName(String name) {
        if (name == null) return "Track";
        name = name.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "Track";
        if (name.length() > 80) name = name.substring(0, 80);
        return name;
    }

    public static String exportName(String trackName, int level) {
        return safeName(trackName) + (level == 0 ? " - instrumental" : " - vocals " + level) + ".wav";
    }

    private static void checkCancel(Progress progress) {
        if (Thread.currentThread().isInterrupted() || (progress != null && progress.isCancelled())) {
            throw new CancellationException("Cancelled");
        }
    }
    private static void ascii(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }
    private static void le16(OutputStream out, int value) throws IOException {
        out.write(value & 255); out.write((value >>> 8) & 255);
    }
    private static void le32(OutputStream out, long value) throws IOException {
        out.write((int) (value & 255)); out.write((int) ((value >>> 8) & 255));
        out.write((int) ((value >>> 16) & 255)); out.write((int) ((value >>> 24) & 255));
    }
}
