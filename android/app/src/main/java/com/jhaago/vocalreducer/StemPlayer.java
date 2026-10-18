package com.jhaago.vocalreducer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One AudioTrack, one sample clock, two aligned PCM files. */
public final class StemPlayer implements AutoCloseable {
    private final TrackSession session;
    private final AudioManager manager;
    private final AudioAttributes attributes;
    private final AudioFocusRequest focus;
    private final Consumer<String> onError;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private volatile float gain = 0.25f;
    private volatile boolean playing;
    private volatile boolean closed;
    private AudioTrack track;
    private long position;
    private long baseFrame;
    private int generation;

    public StemPlayer(Context context, TrackSession session, Consumer<String> onError) throws IOException {
        PcmFiles.validatePair(session.original(), session.instrumental(), session.frames);
        this.session = session;
        this.onError = onError;
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> {
                    if (change != AudioManager.AUDIOFOCUS_GAIN) pause();
                }).build();
    }

    public void setLevel(int level, boolean original) { gain = original ? 1f : MixMath.gain(level); }
    public boolean isPlaying() { return playing; }
    public long framePosition() {
        synchronized (lock) { return positionLocked(); }
    }
    private long positionLocked() {
        if (track != null && playing) {
            try { return Math.min(session.frames, baseFrame + Integer.toUnsignedLong(track.getPlaybackHeadPosition())); }
            catch (IllegalStateException released) { return position; }
        }
        return position;
    }

    public void play() {
        synchronized (lock) {
            if (closed || playing) return;
            if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                onError.accept("Audio is in use by another app. Try playing again in a moment.");
                return;
            }
            if (position >= session.frames) position = 0;
            playing = true;
            int job = ++generation;
            long from = position;
            executor.execute(() -> stream(job, from));
        }
    }

    public void pause() {
        synchronized (lock) {
            if (!playing) return;
            position = positionLocked();
            playing = false;
            generation++;
            if (track != null) {
                try { track.pause(); track.flush(); } catch (IllegalStateException ignored) {}
                // The old worker still owns its local AudioTrack and will release it.
                // Do not publish its reset clock while a new generation is queued.
                track = null;
            }
            manager.abandonAudioFocusRequest(focus);
        }
    }

    public void seek(long frame) {
        boolean resume = playing;
        pause();
        synchronized (lock) { position = Math.max(0, Math.min(session.frames, frame)); }
        if (resume) play();
    }

    private boolean current(int job) {
        synchronized (lock) { return !closed && playing && generation == job; }
    }

    private void stream(int job, long from) {
        AudioTrack output = null;
        boolean reachedEnd = false;
        try (RandomAccessFile x = new RandomAccessFile(session.original(), "r");
             RandomAccessFile i = new RandomAccessFile(session.instrumental(), "r")) {
            if (!current(job)) return;
            int minimum = AudioTrack.getMinBufferSize(PcmFiles.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum < 1) throw new IOException("This phone could not open stereo audio playback.");
            output = new AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(PcmFiles.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum, 8192)).build();
            if (output.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Could not start audio playback.");
            synchronized (lock) {
                if (!current(job)) return;
                track = output;
                baseFrame = from;
                output.play();
            }
            x.seek(from * 8); i.seek(from * 8);
            float[] original = new float[1024];
            float[] instrumental = new float[1024];
            byte[] readBuffer = new byte[4096];
            short[] mixed = new short[1024];
            float smoothed = gain;
            long next = from;
            while (next < session.frames && current(job)) {
                int count = (int) Math.min(512, session.frames - next) * 2;
                PcmFiles.readFloats(x, original, count, readBuffer);
                PcmFiles.readFloats(i, instrumental, count, readBuffer);
                float target = gain;
                for (int s = 0; s < count; s += 2) {
                    // A 10 ms maximum full-scale ramp avoids clicks as the control moves.
                    smoothed += Math.max(-1f / 441, Math.min(1f / 441, target - smoothed));
                    mixed[s] = MixMath.pcm16(MixMath.mix(original[s], instrumental[s], smoothed));
                    mixed[s + 1] = MixMath.pcm16(MixMath.mix(original[s + 1], instrumental[s + 1], smoothed));
                }
                int offset = 0;
                while (offset < count && current(job)) {
                    int written = output.write(mixed, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING);
                    if (written < 0) throw new IOException("Playback was interrupted by the phone. Tap Play to retry.");
                    if (written == 0) Thread.sleep(4);
                    else offset += written;
                }
                next += count / 2;
            }
            while (current(job) && Integer.toUnsignedLong(output.getPlaybackHeadPosition()) < session.frames - from) {
                Thread.sleep(15);
            }
            reachedEnd = current(job);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (current(job)) onError.accept(error.getMessage() == null ? "Could not play this track." : error.getMessage());
        } finally {
            synchronized (lock) {
                if (generation == job) {
                    position = reachedEnd ? session.frames : positionLocked();
                    playing = false;
                    manager.abandonAudioFocusRequest(focus);
                }
                if (track == output) track = null;
                if (output != null) {
                    try { output.pause(); output.flush(); } catch (IllegalStateException ignored) {}
                    output.release();
                }
            }
        }
    }

    @Override public void close() {
        pause();
        synchronized (lock) { closed = true; generation++; }
        executor.shutdownNow();
    }
}
