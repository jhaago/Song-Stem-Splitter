package com.jhaago.vocalreducer;

import java.io.IOException;
import java.util.Objects;

/**
 * Bounded-memory stereo PCM resampling with a Blackman-windowed sinc low-pass filter.
 *
 * <p>The caller owns the sink and must copy data if it retains a write buffer. Input/output
 * offsets and counts are in stereo frames, not individual samples. The final output length
 * is round(inputFrames * outputRate / inputRate). Endpoint extension preserves short and
 * constant signals, and the symmetric filter adds no delay to the saved timeline.</p>
 */
public final class StreamingResampler {
    public interface Sink {
        void write(float[] stereo, int offsetFrames, int frameCount) throws IOException;
    }

    static final int MIN_SAMPLE_RATE = 7350;
    static final int MAX_SAMPLE_RATE = 192000;
    private static final int BLOCK_FRAMES = 1024;
    private final int inputRate;
    private final int outputRate;
    private final Sink sink;
    private final int radius;
    private final int taps;
    private final int phaseCount;
    private final float[][] coefficients;
    private final float[] history;
    private final int historyFrames;
    private final float[] block = new float[BLOCK_FRAMES * 2];
    private int blockFrames;
    private long inputFrames;
    private long outputFrames;
    private float firstLeft;
    private float firstRight;
    private float lastLeft;
    private float lastRight;
    private boolean finished;

    public StreamingResampler(int inputRate, int outputRate, Sink sink) {
        if (!supportedRate(inputRate) || !supportedRate(outputRate)) {
            throw new IllegalArgumentException("Sample rates must be between 7350 and 192000 Hz");
        }
        this.inputRate = inputRate;
        this.outputRate = outputRate;
        this.sink = Objects.requireNonNull(sink, "sink");
        if (inputRate == outputRate) {
            radius = taps = phaseCount = historyFrames = 0;
            coefficients = null;
            history = null;
            return;
        }
        double ratio = Math.min(1.0, (double) outputRate / inputRate);
        // Widen the filter when downsampling to retain its attenuation in output Hz.
        radius = (int) Math.ceil(64.0 / ratio);
        taps = 2 * radius;
        phaseCount = Math.min(1024, outputRate / gcd(inputRate, outputRate));
        coefficients = new float[phaseCount + 1][taps];
        double cutoff = 0.94 * ratio;
        for (int phase = 0; phase <= phaseCount; phase++) {
            double fraction = (double) phase / phaseCount;
            double[] kernel = new double[taps];
            double sum = 0;
            for (int tap = 0; tap < taps; tap++) {
                double distance = tap - radius + 1 - fraction;
                double window = Math.abs(distance) >= radius ? 0.0
                        : 0.42 + 0.5 * Math.cos(Math.PI * distance / radius)
                        + 0.08 * Math.cos(2.0 * Math.PI * distance / radius);
                double argument = Math.PI * cutoff * distance;
                double value = cutoff * (Math.abs(argument) < 1e-12 ? 1.0
                        : Math.sin(argument) / argument) * window;
                kernel[tap] = value;
                sum += value;
            }
            for (int tap = 0; tap < taps; tap++) coefficients[phase][tap] = (float) (kernel[tap] / sum);
        }
        historyFrames = taps + 8;
        history = new float[historyFrames * 2];
    }

    public void push(float[] stereo, int offsetFrames, int frameCount) throws IOException {
        ensureOpen();
        Objects.requireNonNull(stereo, "stereo");
        if (offsetFrames < 0 || frameCount < 0 || (long) offsetFrames + frameCount > stereo.length / 2) {
            throw new IndexOutOfBoundsException("Stereo frame range is outside the input buffer");
        }
        int start = offsetFrames * 2;
        int end = start + frameCount * 2;
        for (int i = start; i < end; i++) {
            if (!Float.isFinite(stereo[i])) throw new IOException("Audio contains a non-finite PCM sample");
        }
        if (frameCount == 0) return;
        if (inputRate == outputRate) {
            sink.write(stereo, offsetFrames, frameCount);
            inputFrames += frameCount;
            outputFrames += frameCount;
            return;
        }
        for (int i = start; i < end; i += 2) {
            if (inputFrames == 0) {
                firstLeft = stereo[i];
                firstRight = stereo[i + 1];
            }
            lastLeft = stereo[i];
            lastRight = stereo[i + 1];
            int position = (int) (inputFrames % historyFrames) * 2;
            history[position] = lastLeft;
            history[position + 1] = lastRight;
            inputFrames++;
            while (nextCenter() + radius < inputFrames) appendOutputFrame();
        }
    }

    public long finish() throws IOException {
        ensureOpen();
        finished = true;
        if (inputRate != outputRate && inputFrames != 0) {
            long finalCount = (inputFrames * outputRate + inputRate / 2L) / inputRate;
            while (outputFrames < finalCount) appendOutputFrame();
            flush();
        }
        return outputFrames;
    }

    private long nextCenter() {
        // Integer timing prevents sample-count drift across arbitrary decoder chunk sizes.
        return outputFrames * inputRate / outputRate;
    }

    private void appendOutputFrame() throws IOException {
        long numerator = outputFrames * inputRate;
        long center = numerator / outputRate;
        double phase = (double) (numerator % outputRate) * phaseCount / outputRate;
        int phaseIndex = (int) phase;
        double blend = phase - phaseIndex;
        float[] lower = coefficients[phaseIndex];
        float[] upper = coefficients[phaseIndex + 1];
        double left = 0;
        double right = 0;
        long firstFrame = center - radius + 1;
        for (int tap = 0; tap < taps; tap++) {
            double coefficient = lower[tap] + blend * (upper[tap] - lower[tap]);
            long frame = firstFrame + tap;
            if (frame < 0) {
                left += coefficient * firstLeft;
                right += coefficient * firstRight;
            } else if (frame >= inputFrames) {
                left += coefficient * lastLeft;
                right += coefficient * lastRight;
            } else {
                int position = (int) (frame % historyFrames) * 2;
                left += coefficient * history[position];
                right += coefficient * history[position + 1];
            }
        }
        float leftSample = (float) left;
        float rightSample = (float) right;
        if (!Float.isFinite(leftSample) || !Float.isFinite(rightSample)) {
            throw new IOException("Audio contains PCM values too large to resample safely");
        }
        block[blockFrames * 2] = leftSample;
        block[blockFrames * 2 + 1] = rightSample;
        blockFrames++;
        outputFrames++;
        if (blockFrames == BLOCK_FRAMES) flush();
    }

    private void flush() throws IOException {
        if (blockFrames > 0) {
            sink.write(block, 0, blockFrames);
            blockFrames = 0;
        }
    }

    private void ensureOpen() {
        if (finished) throw new IllegalStateException("Resampling has already finished");
    }

    static boolean supportedRate(int rate) {
        return rate >= MIN_SAMPLE_RATE && rate <= MAX_SAMPLE_RATE;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return a;
    }
}
