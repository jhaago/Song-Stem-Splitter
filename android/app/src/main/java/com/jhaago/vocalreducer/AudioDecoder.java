package com.jhaago.vocalreducer;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Imports one local audio track into private, interleaved stereo float32 LE PCM at 44.1 kHz. */
public final class AudioDecoder {
    private static final int OUTPUT_RATE = 44100;
    private static final long MAX_SECONDS = 15 * 60;
    private static final long MAX_DURATION_US = MAX_SECONDS * 1_000_000L;
    private static final long CODEC_WAIT_US = 10_000;
    private static final long CODEC_STALL_NS = 30_000_000_000L;
    private static final long STORAGE_RESERVE = 8L * 1024 * 1024;
    private static final int CONVERT_FRAMES = 2048;
    // Values are part of AudioFormat since API 31; inlined here to accept reported high-bit PCM
    // without invoking newer APIs on the Android 8 minimum version.
    private static final int PCM_24BIT_PACKED = 21;
    private static final int PCM_32BIT = 22;

    private AudioDecoder() { }

    public interface Callback {
        boolean isCancelled();
        void onProgress(float fraction, String stage);
    }

    /**
     * Decode completely, then promote {@code output + ".partial"} in the same directory.
     * A pre-existing complete output is never replaced. Throws CancellationException when asked
     * to stop, and an actionable IOException for unsupported, empty, overlong or unreadable audio.
     */
    public static long decode(Context context, Uri source, File output, Callback callback) throws Exception {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(callback, "callback");
        checkCancelled(callback);
        String scheme = source.getScheme();
        if (!"content".equals(scheme) && !"file".equals(scheme)) {
            throw new IOException("Choose a local audio file from the document picker.");
        }
        Progress progress = new Progress(callback);
        progress.report(0f, "Opening audio", true);
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try (PcmFileWriter writer = new PcmFileWriter(output, callback)) {
            // Preserve a content provider's descriptor offset and declared length, then close
            // our descriptor; MediaExtractor holds its own reference after setDataSource returns.
            try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(source, "r")) {
                if (descriptor == null) throw new IOException("The selected audio file could not be opened.");
                checkCancelled(callback);
                long length = descriptor.getDeclaredLength();
                if (length >= 0) {
                    extractor.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), length);
                } else if (descriptor.getStartOffset() == 0) {
                    extractor.setDataSource(descriptor.getFileDescriptor());
                } else {
                    throw new IOException("This file provider did not supply a usable audio length. Save a local copy and try again.");
                }
            }
            checkCancelled(callback);
            int track = -1;
            MediaFormat inputFormat = null;
            String mime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                checkCancelled(callback);
                MediaFormat candidate = extractor.getTrackFormat(i);
                String candidateMime = candidate.getString(MediaFormat.KEY_MIME);
                if (candidateMime != null && candidateMime.startsWith("audio/")) {
                    track = i;
                    inputFormat = candidate;
                    mime = candidateMime;
                    break;
                }
            }
            if (track < 0) throw new IOException("No audio track was found. Choose a supported MP3, M4A or WAV file.");
            if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                validateChannels(inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            }
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : -1;
            if (durationUs > MAX_DURATION_US) throw tooLong();
            if (durationUs > 0) {
                requireStorage(output.getAbsoluteFile().getParentFile(),
                        durationUs * OUTPUT_RATE * 8 / 1_000_000L + STORAGE_RESERVE);
            }
            extractor.selectTrack(track);
            checkCancelled(callback);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            checkCancelled(callback);
            codec.start();
            PcmPipeline pipeline = new PcmPipeline(writer, callback);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnded = false;
            boolean outputEnded = false;
            long lastActivity = System.nanoTime();
            progress.report(0.01f, "Decoding audio", true);
            while (!outputEnded) {
                checkCancelled(callback);
                if (!inputEnded) {
                    int index = codec.dequeueInputBuffer(CODEC_WAIT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        if (buffer == null) throw new IOException("The Android audio decoder returned an unavailable input buffer.");
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        checkCancelled(callback);
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            // At EOF getSampleFlags() can return -1, which has every bit set.
                            // Inspect encryption only after confirming a sample exists.
                            int sampleFlags = extractor.getSampleFlags();
                            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                                throw new IOException("Protected audio cannot be imported. Choose a DRM-free local audio file.");
                            }
                            if (size > buffer.capacity()) throw new IOException("The audio file contains an oversized encoded frame.");
                            int flags = (sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0
                                    ? MediaCodec.BUFFER_FLAG_PARTIAL_FRAME : 0;
                            codec.queueInputBuffer(index, 0, size, Math.max(0, extractor.getSampleTime()), flags);
                            extractor.advance();
                        }
                        lastActivity = System.nanoTime();
                    }
                }
                checkCancelled(callback);
                int index = codec.dequeueOutputBuffer(info, CODEC_WAIT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    pipeline.configure(codec.getOutputFormat());
                    lastActivity = System.nanoTime();
                } else if (index >= 0) {
                    try {
                        if (info.size < 0) throw new IOException("The Android audio decoder returned a negative PCM buffer size.");
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (!pipeline.configured()) pipeline.configure(codec.getOutputFormat());
                            ByteBuffer buffer = codec.getOutputBuffer(index);
                            if (buffer == null || info.offset < 0 || info.size < 0
                                    || (long) info.offset + info.size > buffer.capacity()) {
                                throw new IOException("The Android audio decoder returned an invalid PCM buffer.");
                            }
                            ByteBuffer pcm = buffer.duplicate().order(ByteOrder.nativeOrder());
                            pcm.clear();
                            pcm.limit(info.offset + info.size);
                            pcm.position(info.offset);
                            pipeline.consume(pcm);
                            double totalUs = pipeline.inputFrames * 1_000_000.0 / pipeline.sampleRate;
                            float fraction = (float) Math.min(0.99, totalUs / (durationUs > 0 ? durationUs : MAX_DURATION_US));
                            progress.report(fraction, "Decoding and resampling audio", false);
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        lastActivity = System.nanoTime();
                    } finally {
                        codec.releaseOutputBuffer(index, false);
                    }
                }
                if (System.nanoTime() - lastActivity > CODEC_STALL_NS) {
                    throw new IOException("The Android audio decoder stopped responding. Try another local copy or convert the track to WAV.");
                }
            }
            checkCancelled(callback);
            if (!pipeline.configured() || pipeline.inputFrames == 0) {
                throw new IOException("The file contains no decodable audio. Choose another audio file.");
            }
            long frames = pipeline.resampler.finish();
            if (frames == 0) throw new IOException("The file is too short to contain a complete output audio frame.");
            if (frames != writer.framesWritten) {
                throw new IOException("The imported audio frame count did not match the saved file.");
            }
            checkCancelled(callback);
            progress.report(1f, "Audio import complete", true);
            return writer.commit();
        } catch (CancellationException error) {
            throw error;
        } catch (MediaCodec.CodecException error) {
            throw new IOException("Android could not decode this audio format. Try a DRM-free MP3, M4A or PCM WAV copy.", error);
        } catch (SecurityException error) {
            throw new IOException("Access to this audio file was lost. Choose it again using the document picker.", error);
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new IOException("The audio file is unsupported or damaged. Try another local copy or convert it to PCM WAV.", error);
        } catch (IOException error) {
            throw new IOException("Audio import failed: " + error.getMessage(), error);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) { /* May not have started. */ }
                try { codec.release(); } catch (RuntimeException ignored) { /* Best effort after codec failure. */ }
            }
            try { extractor.release(); } catch (RuntimeException ignored) { /* Best effort after extractor failure. */ }
        }
    }

    static int readPcmFrames(ByteBuffer input, int channels, int encoding, float[] stereo,
                             int maxFrames) throws IOException {
        validateChannels(channels);
        int bytesPerFrame = bytesPerSample(encoding) * channels;
        if (input.remaining() % bytesPerFrame != 0) {
            throw new IOException("The audio decoder returned an incomplete PCM frame. The file may be damaged.");
        }
        if (maxFrames < 0 || maxFrames > stereo.length / 2) throw new IllegalArgumentException("PCM destination is too small");
        input.order(ByteOrder.nativeOrder());
        int frames = Math.min(maxFrames, input.remaining() / bytesPerFrame);
        for (int i = 0; i < frames; i++) {
            float left = readSample(input, encoding);
            float right = channels == 1 ? left : readSample(input, encoding);
            if (!Float.isFinite(left) || !Float.isFinite(right)) {
                throw new IOException("The audio contains invalid non-finite samples. Choose another copy of the track.");
            }
            stereo[2 * i] = left;
            stereo[2 * i + 1] = right;
        }
        return frames;
    }

    private static float readSample(ByteBuffer input, int encoding) {
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_16BIT: return input.getShort() / 32768.0f;
            case AudioFormat.ENCODING_PCM_8BIT: return ((input.get() & 255) - 128) / 128.0f;
            case AudioFormat.ENCODING_PCM_FLOAT: return input.getFloat();
            case PCM_32BIT: return (float) (input.getInt() / 2147483648.0);
            case PCM_24BIT_PACKED:
                int a = input.get() & 255;
                int b = input.get() & 255;
                int c = input.get() & 255;
                int bits = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                        ? a | (b << 8) | (c << 16) : (a << 16) | (b << 8) | c;
                return ((bits << 8) >> 8) / 8388608.0f;
            default: throw new AssertionError("PCM encoding must be validated before reading");
        }
    }

    private static int bytesPerSample(int encoding) throws IOException {
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT: return 1;
            case AudioFormat.ENCODING_PCM_16BIT: return 2;
            case PCM_24BIT_PACKED: return 3;
            case AudioFormat.ENCODING_PCM_FLOAT:
            case PCM_32BIT: return 4;
            default: throw new IOException("This decoder's PCM encoding (" + encoding
                    + ") is unsupported. Convert the track to a 16-bit PCM WAV file and try again.");
        }
    }

    private static void validateChannels(int channels) throws IOException {
        if (channels != 1 && channels != 2) {
            throw new IOException("This first build supports mono or stereo audio. Convert this "
                    + channels + "-channel track to stereo and try again.");
        }
    }

    static void validateInputFrameCount(long count, int sampleRate) throws IOException {
        if (!StreamingResampler.supportedRate(sampleRate)) {
            throw new IOException("This sample rate (" + sampleRate
                    + " Hz) is unsupported. Convert the track to 44.1 or 48 kHz and try again.");
        }
        if (count < 0 || count > sampleRate * MAX_SECONDS) throw tooLong();
    }

    private static IOException tooLong() {
        return new IOException("This first build supports tracks up to 15 minutes. Choose a shorter track; no audio was trimmed.");
    }

    private static void checkCancelled(Callback callback) {
        if (Thread.currentThread().isInterrupted() || callback.isCancelled()) {
            throw new CancellationException("Audio import cancelled");
        }
    }

    private static void requireStorage(File directory, long bytes) throws IOException {
        if (directory.getUsableSpace() < bytes) {
            throw new IOException("Not enough free storage to import this track. Free space on your phone and try again.");
        }
    }

    private static final class PcmPipeline {
        final PcmFileWriter writer;
        final Callback callback;
        final float[] samples = new float[CONVERT_FRAMES * 2];
        StreamingResampler resampler;
        int sampleRate;
        int channels;
        int encoding;
        long inputFrames;

        PcmPipeline(PcmFileWriter writer, Callback callback) {
            this.writer = writer;
            this.callback = callback;
        }

        boolean configured() { return resampler != null; }

        void configure(MediaFormat format) throws IOException {
            checkCancelled(callback);
            int newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int newChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int newEncoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
            validateChannels(newChannels);
            validateInputFrameCount(0, newRate);
            bytesPerSample(newEncoding);
            if (configured() && (newRate != sampleRate || newChannels != channels || newEncoding != encoding)) {
                if (inputFrames != 0) {
                    throw new IOException("The audio format changes within this track. Convert it to a single-format PCM WAV file and try again.");
                }
                resampler = null;
            }
            sampleRate = newRate;
            channels = newChannels;
            encoding = newEncoding;
            if (resampler == null) resampler = new StreamingResampler(sampleRate, OUTPUT_RATE, writer);
            checkCancelled(callback);
        }

        void consume(ByteBuffer input) throws IOException {
            int bytesPerFrame = bytesPerSample(encoding) * channels;
            if (input.remaining() % bytesPerFrame != 0) {
                throw new IOException("The audio decoder returned an incomplete PCM frame. The file may be damaged.");
            }
            validateInputFrameCount(inputFrames + input.remaining() / bytesPerFrame, sampleRate);
            while (input.hasRemaining()) {
                checkCancelled(callback);
                int frames = readPcmFrames(input, channels, encoding, samples, CONVERT_FRAMES);
                resampler.push(samples, 0, frames);
                inputFrames += frames;
            }
        }
    }

    private static final class Progress {
        final Callback callback;
        float previous;
        long reportedAt;

        Progress(Callback callback) { this.callback = callback; }

        void report(float fraction, String stage, boolean force) {
            checkCancelled(callback);
            long now = System.nanoTime();
            if (force || now - reportedAt >= 100_000_000L) {
                previous = Math.max(previous, Math.max(0, Math.min(1, fraction)));
                callback.onProgress(previous, stage);
                reportedAt = now;
            }
        }
    }

    /** A transaction around the canonical file; independently testable without an Android device. */
    static final class PcmFileWriter implements StreamingResampler.Sink, AutoCloseable {
        final File output;
        final File partial;
        final Callback callback;
        final ByteBuffer bytes = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
        FileOutputStream stream;
        long framesWritten;
        long nextSpaceCheck;
        boolean promoted;

        PcmFileWriter(File output, Callback callback) throws IOException {
            this.output = output.getAbsoluteFile();
            this.partial = new File(this.output.getPath() + ".partial");
            this.callback = Objects.requireNonNull(callback, "callback");
            checkCancelled(callback);
            if (this.output.exists()) throw new IOException("A completed audio import already exists at this destination.");
            File directory = this.output.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("The private audio storage directory could not be created.");
            }
            if (partial.exists() && !partial.delete()) {
                throw new IOException("The previous incomplete import could not be cleared. Restart the app and try again.");
            }
            requireStorage(directory, STORAGE_RESERVE);
            try {
                stream = new FileOutputStream(partial);
            } catch (IOException error) {
                throw new IOException("Could not create the private audio file. Check free storage and try again.", error);
            }
        }

        @Override public void write(float[] samples, int offsetFrames, int frames) throws IOException {
            checkCancelled(callback);
            if (stream == null) throw new IOException("The audio output is already closed.");
            if (offsetFrames < 0 || frames < 0 || (long) offsetFrames + frames > samples.length / 2) {
                throw new IndexOutOfBoundsException("Stereo frame range is outside the write buffer");
            }
            if (framesWritten + frames > OUTPUT_RATE * MAX_SECONDS) throw tooLong();
            for (int i = offsetFrames * 2; i < (offsetFrames + frames) * 2; i++) {
                if (!Float.isFinite(samples[i])) throw new IOException("Audio contains a non-finite PCM sample");
            }
            int position = offsetFrames;
            int end = offsetFrames + frames;
            while (position < end) {
                checkCancelled(callback);
                int count = Math.min(bytes.capacity() / 8, end - position);
                if (framesWritten * 8 >= nextSpaceCheck) {
                    requireStorage(output.getParentFile(), STORAGE_RESERVE + count * 8L);
                    nextSpaceCheck = framesWritten * 8 + 1024 * 1024;
                }
                bytes.clear();
                for (int i = 0; i < count * 2; i++) bytes.putFloat(samples[position * 2 + i]);
                try {
                    stream.write(bytes.array(), 0, count * 8);
                } catch (IOException error) {
                    throw new IOException("Could not save the imported audio. Check free storage and try again.", error);
                }
                framesWritten += count;
                position += count;
            }
        }

        long commit() throws IOException {
            checkCancelled(callback);
            if (stream == null) throw new IOException("The audio output is already closed.");
            if (framesWritten == 0) throw new IOException("The file contains no decodable audio.");
            try {
                stream.getFD().sync();
                stream.close();
                stream = null;
            } catch (IOException error) {
                throw new IOException("Could not finish saving the imported audio. Check free storage and try again.", error);
            }
            checkCancelled(callback);
            if (partial.length() != framesWritten * 8) throw new IOException("The imported audio file is incomplete. Check free storage and try again.");
            if (output.exists() || !partial.renameTo(output)) {
                throw new IOException("The completed audio import could not be saved. Check free storage and try again.");
            }
            promoted = true;
            return framesWritten;
        }

        @Override public void close() throws IOException {
            IOException failure = null;
            if (stream != null) {
                try { stream.close(); } catch (IOException error) { failure = error; }
                stream = null;
            }
            if (!promoted && partial.exists() && !partial.delete()) {
                IOException cleanup = new IOException("The incomplete audio import could not be removed. Restart the app to retry cleanup.");
                if (failure != null) cleanup.addSuppressed(failure);
                failure = cleanup;
            }
            if (failure != null) throw failure;
        }
    }
}
