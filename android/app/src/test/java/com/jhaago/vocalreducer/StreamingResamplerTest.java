package com.jhaago.vocalreducer;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class StreamingResamplerTest {
    @Test public void sameRatePreservesStereoSamplesExactly() throws Exception {
        float[] input = {0.2f, -0.8f, 1f, -1f, 0f, 0.12f};
        assertArrayEquals(input, convert(input, 44100, 44100, 2), 0f);
    }

    @Test public void outputDurationUsesNearestWholeFrameForCommonRates() throws Exception {
        for (int rate : new int[]{7350, 8000, 16000, 22050, 32000, 44100, 48000, 96000, 192000}) {
            int frames = rate / 5 + 17;
            float[] actual = convert(new float[frames * 2], rate, 44100, 193);
            assertEquals("rate " + rate,
                    ((long) frames * 44100 + rate / 2) / rate, actual.length / 2);
            for (float sample : actual) assertEquals(0f, sample, 0f);
        }
    }

    @Test public void chunkBoundariesDoNotChangeTheWaveform() throws Exception {
        float[] input = tone(12013, 48000, 7132, 0.73);
        float[] whole = convert(input, 48000, 44100, input.length / 2);
        assertTrue(whole.length > 20000);
        assertArrayEquals(whole, convert(input, 48000, 44100, 1), 0f);
        assertArrayEquals(whole, convert(input, 48000, 44100, 257), 0f);
    }

    @Test public void downsamplingRejectsEnergyAboveOutputNyquist() throws Exception {
        float[] passBand = convert(tone(48000, 48000, 1000, 0.8), 48000, 44100, 1023);
        float[] stopBand = convert(tone(48000, 48000, 23000, 0.8), 48000, 44100, 1023);
        double passRms = rms(passBand, 2048);
        double stopRms = rms(stopBand, 2048);
        assertTrue("1 kHz RMS must be preserved, was " + passRms,
                Math.abs(passRms - 0.8 / Math.sqrt(2)) < 0.002);
        assertTrue("23 kHz alias must be suppressed at least 65 dB, ratio " + stopRms / passRms,
                stopRms / passRms < Math.pow(10, -65.0 / 20));
    }

    @Test public void highRateDownsamplingAlsoRejectsAliasing() throws Exception {
        float[] actual = convert(tone(48000, 96000, 30000, 0.8), 96000, 44100, 333);
        assertTrue("30 kHz must not alias into a 44.1 kHz output", rms(actual, 1024) < 0.0005);
    }

    @Test public void upsamplingPreservesTonePitchAndStereoPhase() throws Exception {
        float[] actual = convert(tone(8000, 16000, 997, 0.6), 16000, 44100, 113);
        assertEquals(22050, actual.length / 2);
        double squaredError = 0;
        int count = 0;
        for (int frame = 1024; frame < actual.length / 2 - 1024; frame++) {
            double expected = 0.6 * Math.sin(2 * Math.PI * 997 * frame / 44100.0);
            double error = actual[2 * frame] - expected;
            squaredError += error * error;
            assertEquals(-actual[2 * frame], actual[2 * frame + 1], 1e-6f);
            count++;
        }
        assertTrue("Resampled tone must retain its original pitch and phase",
                Math.sqrt(squaredError / count) < 0.0001);
    }

    @Test public void oneFrameAndVeryShortSignalsRetainDcAtBothEnds() throws Exception {
        for (int frames : new int[]{1, 2, 31, 139}) {
            float[] input = new float[2 * frames];
            for (int i = 0; i < frames; i++) {
                input[2 * i] = 0.37f;
                input[2 * i + 1] = -0.19f;
            }
            float[] result = convert(input, 8000, 44100, 7);
            assertEquals(((long) frames * 44100 + 4000) / 8000, result.length / 2);
            for (int i = 0; i < result.length / 2; i++) {
                assertEquals(0.37f, result[2 * i], 0.000001f);
                assertEquals(-0.19f, result[2 * i + 1], 0.000001f);
            }
        }
    }

    @Test public void emptyStreamHasNoFabricatedSamples() throws Exception {
        Collect sink = new Collect();
        StreamingResampler resampler = new StreamingResampler(48000, 44100, sink);
        resampler.push(new float[0], 0, 0);
        assertEquals(0, resampler.finish());
        assertEquals(0, sink.samples().length);
        assertThrows(IllegalStateException.class, () -> resampler.push(new float[]{0, 0}, 0, 1));
    }

    @Test public void nonFiniteInputIsRejectedBeforeAnyOutputFromThatChunk() throws Exception {
        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            Collect sink = new Collect();
            StreamingResampler resampler = new StreamingResampler(44100, 44100, sink);
            assertThrows(IOException.class, () -> resampler.push(new float[]{0.2f, bad}, 0, 1));
            assertEquals(0, sink.samples().length);
        }
    }

    @Test public void invalidRatesAndRangesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new StreamingResampler(0, 44100, new Collect()));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamingResampler(48000, 0, new Collect()));
        StreamingResampler resampler = new StreamingResampler(48000, 44100, new Collect());
        assertThrows(IndexOutOfBoundsException.class, () -> resampler.push(new float[4], 1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> resampler.push(new float[4], -1, 1));
    }

    @Test public void sinkFailurePropagatesWithoutAReportedSuccess() throws Exception {
        StreamingResampler resampler = new StreamingResampler(44100, 44100,
                (samples, offset, frames) -> { throw new IOException("disk full"); });
        IOException error = assertThrows(IOException.class,
                () -> resampler.push(new float[]{0.1f, 0.2f}, 0, 1));
        assertEquals("disk full", error.getMessage());
    }

    private static float[] convert(float[] input, int inputRate, int outputRate,
                                   int chunkFrames) throws Exception {
        Collect sink = new Collect();
        StreamingResampler resampler = new StreamingResampler(inputRate, outputRate, sink);
        for (int offset = 0; offset < input.length / 2; offset += chunkFrames) {
            resampler.push(input, offset, Math.min(chunkFrames, input.length / 2 - offset));
        }
        long frames = resampler.finish();
        float[] result = sink.samples();
        assertEquals("Reported frame count must equal actual output", result.length / 2, frames);
        return result;
    }

    private static float[] tone(int frames, int sampleRate, double frequency, double amplitude) {
        float[] result = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            result[2 * i] = (float) (amplitude * Math.sin(2 * Math.PI * frequency * i / sampleRate));
            result[2 * i + 1] = -result[2 * i];
        }
        return result;
    }

    private static double rms(float[] samples, int excludedFrames) {
        int end = samples.length / 2 - excludedFrames;
        assertTrue("Fixture must have a measurable interior", end > excludedFrames);
        double sum = 0;
        for (int i = excludedFrames; i < end; i++) sum += samples[2 * i] * samples[2 * i];
        return Math.sqrt(sum / (end - excludedFrames));
    }

    private static final class Collect implements StreamingResampler.Sink {
        private final List<float[]> chunks = new ArrayList<>();
        private int sampleCount;

        @Override public void write(float[] stereo, int offsetFrames, int frames) {
            float[] copy = new float[frames * 2];
            System.arraycopy(stereo, offsetFrames * 2, copy, 0, copy.length);
            chunks.add(copy);
            sampleCount += copy.length;
        }

        float[] samples() {
            float[] all = new float[sampleCount];
            int position = 0;
            for (float[] chunk : chunks) {
                System.arraycopy(chunk, 0, all, position, chunk.length);
                position += chunk.length;
            }
            return all;
        }
    }
}
