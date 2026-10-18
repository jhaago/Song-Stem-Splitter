package com.jhaago.vocalreducer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

public class AudioDecoderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void signedPcm16MonoIsDuplicatedWithoutChangingGain() throws Exception {
        ByteBuffer input = ByteBuffer.allocate(6).order(ByteOrder.nativeOrder());
        input.putShort(Short.MIN_VALUE).putShort((short) 16384).putShort(Short.MAX_VALUE).flip();
        float[] actual = new float[6];
        assertEquals(3, AudioDecoder.readPcmFrames(input, 1, 2, actual, 3));
        assertArrayEquals(new float[]{-1, -1, 0.5f, 0.5f, 32767f / 32768, 32767f / 32768}, actual, 0f);
    }

    @Test public void floatStereoRetainsChannelOrderAndBufferOffset() throws Exception {
        ByteBuffer input = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());
        input.putFloat(99).putFloat(99).putFloat(0.25f).putFloat(-0.75f).putFloat(1.2f).putFloat(-1.2f);
        input.flip();
        input.position(8);
        float[] actual = new float[4];
        assertEquals(2, AudioDecoder.readPcmFrames(input, 2, 4, actual, 2));
        assertArrayEquals(new float[]{0.25f, -0.75f, 1.2f, -1.2f}, actual, 0f);
        assertFalse(input.hasRemaining());
    }

    @Test public void unsignedPcm8AndSignedPcm32UseCorrectScale() throws Exception {
        float[] actual = new float[6];
        assertEquals(3, AudioDecoder.readPcmFrames(ByteBuffer.wrap(new byte[]{0, (byte) 128, (byte) 255}),
                1, 3, actual, 3));
        assertArrayEquals(new float[]{-1, -1, 0, 0, 127f / 128, 127f / 128}, actual, 0f);
        ByteBuffer input = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder());
        input.putInt(Integer.MIN_VALUE).putInt(1 << 30).putInt(Integer.MAX_VALUE).flip();
        assertEquals(3, AudioDecoder.readPcmFrames(input, 1, 22, actual, 3));
        assertArrayEquals(new float[]{-1, -1, 0.5f, 0.5f, 1, 1}, actual, 0f);
    }

    @Test public void packedPcm24SignExtendsAllThreeBytes() throws Exception {
        ByteBuffer input = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder());
        for (int value : new int[]{-8388608, -1, 0, 4194304}) {
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                input.put((byte) value).put((byte) (value >> 8)).put((byte) (value >> 16));
            } else {
                input.put((byte) (value >> 16)).put((byte) (value >> 8)).put((byte) value);
            }
        }
        input.flip();
        float[] actual = new float[8];
        assertEquals(4, AudioDecoder.readPcmFrames(input, 1, 21, actual, 4));
        assertArrayEquals(new float[]{-1, -1, -1f / 8388608, -1f / 8388608, 0, 0, 0.5f, 0.5f}, actual, 0f);
    }

    @Test public void multichannelUnknownEncodingAndPartialFramesAreExplicitErrors() {
        assertThrows(IOException.class, () -> AudioDecoder.readPcmFrames(ByteBuffer.allocate(24), 6, 2, new float[8], 4));
        assertThrows(IOException.class, () -> AudioDecoder.readPcmFrames(ByteBuffer.allocate(8), 2, 999, new float[4], 2));
        assertThrows(IOException.class, () -> AudioDecoder.readPcmFrames(ByteBuffer.allocate(3), 2, 2, new float[4], 2));
    }

    @Test public void nonFiniteDecodedFloatIsRejected() {
        ByteBuffer input = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        input.putFloat(0).putFloat(Float.NaN).flip();
        assertThrows(IOException.class, () -> AudioDecoder.readPcmFrames(input, 2, 4, new float[2], 1));
    }

    @Test public void fifteenMinuteLimitRejectsTheFirstExtraFrame() throws Exception {
        AudioDecoder.validateInputFrameCount(48000L * 900, 48000);
        IOException error = assertThrows(IOException.class,
                () -> AudioDecoder.validateInputFrameCount(48000L * 900 + 1, 48000));
        assertTrue(error.getMessage().contains("15 minutes"));
    }

    @Test public void committedPcmIsLittleEndianAndLengthMatchesFrameCount() throws Exception {
        File output = new File(temporary.getRoot(), "source.pcm");
        try (AudioDecoder.PcmFileWriter writer = new AudioDecoder.PcmFileWriter(output, new TestCallback())) {
            writer.write(new float[]{0.25f, -0.5f, 1f, -1f}, 0, 2);
            assertFalse(output.exists());
            assertEquals(2, writer.commit());
        }
        byte[] bytes = Files.readAllBytes(output.toPath());
        assertEquals(16, bytes.length);
        ByteBuffer actual = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0.25f, actual.getFloat(), 0f);
        assertEquals(-0.5f, actual.getFloat(), 0f);
        assertEquals(1f, actual.getFloat(), 0f);
        assertEquals(-1f, actual.getFloat(), 0f);
        assertFalse(new File(output + ".partial").exists());
    }

    @Test public void cancelledImportRemovesPartialFileAndNeverPromotesIt() throws Exception {
        File output = new File(temporary.getRoot(), "cancelled.pcm");
        TestCallback callback = new TestCallback();
        try (AudioDecoder.PcmFileWriter writer = new AudioDecoder.PcmFileWriter(output, callback)) {
            writer.write(new float[]{0.25f, -0.5f}, 0, 1);
            callback.cancelled = true;
            assertThrows(CancellationException.class, writer::commit);
        }
        assertFalse(output.exists());
        assertFalse(new File(output + ".partial").exists());
    }

    @Test public void writeFailureCleansUpPartialAndPreservesPreviousCompleteFile() throws Exception {
        File output = new File(temporary.getRoot(), "previous.pcm");
        byte[] previous = {1, 2, 3, 4};
        Files.write(output.toPath(), previous);
        assertThrows(IOException.class, () -> new AudioDecoder.PcmFileWriter(output, new TestCallback()));
        assertArrayEquals(previous, Files.readAllBytes(output.toPath()));
        File rejected = new File(temporary.getRoot(), "nonfinite.pcm");
        try (AudioDecoder.PcmFileWriter writer = new AudioDecoder.PcmFileWriter(rejected, new TestCallback())) {
            assertThrows(IOException.class, () -> writer.write(new float[]{0, Float.POSITIVE_INFINITY}, 0, 1));
        }
        assertFalse(rejected.exists());
        assertFalse(new File(rejected + ".partial").exists());
    }

    @Test public void emptyImportCannotBePromoted() throws Exception {
        File output = new File(temporary.getRoot(), "empty.pcm");
        try (AudioDecoder.PcmFileWriter writer = new AudioDecoder.PcmFileWriter(output, new TestCallback())) {
            assertThrows(IOException.class, writer::commit);
        }
        assertFalse(output.exists());
        assertFalse(new File(output + ".partial").exists());
    }

    private static final class TestCallback implements AudioDecoder.Callback {
        boolean cancelled;
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void onProgress(float fraction, String stage) { }
    }
}
