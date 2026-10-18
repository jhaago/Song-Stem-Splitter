#!/usr/bin/env python3
"""Exercise StemPlayer generation ownership without Android hardware.

The fake AudioTrack pauses the stream worker inside write() so the UI can perform
pause/play/pause before the old worker's finally block runs. flush() resets the
fake hardware clock as Android's streaming AudioTrack does. No Android classes
from these stubs are included in the app or APK.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

STUBS = {'android/content/Context.java': 'package android.content; public class Context { public static final String AUDIO_SERVICE="audio"; public Object getSystemService(String x) { return new android.media.AudioManager(); } }', 'android/media/AudioAttributes.java': 'package android.media; public class AudioAttributes { public static final int USAGE_MEDIA=1, CONTENT_TYPE_MUSIC=2; public static class Builder { public Builder setUsage(int x){return this;} public Builder setContentType(int x){return this;} public AudioAttributes build(){return new AudioAttributes();} } }', 'android/media/AudioFocusRequest.java': 'package android.media; public class AudioFocusRequest { public interface Listener {void onChange(int x);} public static class Builder { public Builder(int x){} public Builder setAudioAttributes(AudioAttributes x){return this;} public Builder setAcceptsDelayedFocusGain(boolean x){return this;} public Builder setOnAudioFocusChangeListener(Listener x){return this;} public AudioFocusRequest build(){return new AudioFocusRequest();} } }', 'android/media/AudioFormat.java': 'package android.media; public class AudioFormat { public static final int CHANNEL_OUT_STEREO=12, ENCODING_PCM_16BIT=2; public static class Builder { public Builder setSampleRate(int x){return this;} public Builder setChannelMask(int x){return this;} public Builder setEncoding(int x){return this;} public AudioFormat build(){return new AudioFormat();} } }', 'android/media/AudioManager.java': 'package android.media; public class AudioManager { public static final int AUDIOFOCUS_GAIN=1, AUDIOFOCUS_REQUEST_GRANTED=1; public int requestAudioFocus(AudioFocusRequest r){return 1;} public void abandonAudioFocusRequest(AudioFocusRequest r){} }', 'android/media/AudioTrack.java': 'package android.media; import java.util.concurrent.*; public class AudioTrack { public static final int STATE_INITIALIZED=1, MODE_STREAM=1, WRITE_NON_BLOCKING=1; public static volatile AudioTrack last; public static final CountDownLatch inWrite=new CountDownLatch(1), releaseWrite=new CountDownLatch(1); public volatile int head; public AudioTrack(){last=this;} public static int getMinBufferSize(int a,int b,int c){return 8192;} public int getState(){return 1;} public void play(){} public void pause(){} public void flush(){head=0;} public int getPlaybackHeadPosition(){return head;} public int write(short[]a,int b,int c,int d){ inWrite.countDown();try{releaseWrite.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return c;} public void release(){} public static class Builder { public Builder setAudioAttributes(AudioAttributes x){return this;} public Builder setAudioFormat(AudioFormat x){return this;} public Builder setTransferMode(int x){return this;} public Builder setBufferSizeInBytes(int x){return this;} public AudioTrack build(){return new AudioTrack();} } }'}

HARNESS = r"""
package com.jhaago.vocalreducer;

import android.content.Context;
import android.media.AudioTrack;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

public final class PlayerPauseRegression {
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("vocal-player-pcm").toFile();
        StemPlayer player = null;
        try {
            for (String name : new String[]{"original.pcm", "instrumental.pcm"}) {
                try (RandomAccessFile file = new RandomAccessFile(new File(directory, name), "rw")) {
                    file.setLength(10000 * 8);
                }
            }
            TrackSession session = new TrackSession("test", "Track", 10000, directory);
            player = new StemPlayer(new Context(), session, error -> {
                throw new AssertionError(error);
            });
            player.play();
            if (!AudioTrack.inWrite.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Stream worker did not reach write()");
            }
            AudioTrack.last.head = 500;
            player.pause();
            assertPosition(player, 500, "first pause");
            player.play();
            assertPosition(player, 500, "queued replay before old worker exits");
            player.pause();
            assertPosition(player, 500, "rapid second pause");
            System.out.println("PASS: paused frame500 preserved across queued replay/pause");
        } finally {
            AudioTrack.releaseWrite.countDown();
            if (player != null) player.close();
            for (File file : directory.listFiles()) file.delete();
            directory.delete();
        }
    }

    private static void assertPosition(StemPlayer player, long expected, String stage) {
        long actual = player.framePosition();
        if (actual != expected) {
            throw new AssertionError(stage + ": expected " + expected + ", got " + actual);
        }
    }
}
"""


def main():
    android_root = Path(__file__).resolve().parents[1]
    actual = android_root / "app/src/main/java/com/jhaago/vocalreducer"
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not javac or not java:
        raise SystemExit("Set JAVA_HOME to a JDK (17 or newer), then retry.")
    with tempfile.TemporaryDirectory(prefix="vocal-player-regression-") as temp:
        work = Path(temp)
        sources = []
        for relative, content in STUBS.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(path)
        harness = work / "com/jhaago/vocalreducer/PlayerPauseRegression.java"
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_text(HARNESS)
        sources.append(harness)
        sources.extend(actual / (name + ".java") for name in
                       ("MixMath", "PcmFiles", "TrackSession", "StemPlayer"))
        classes = work / "classes"
        subprocess.run([javac, "-d", str(classes), *map(str, sources)], check=True)
        subprocess.run([java, "-cp", str(classes),
                        "com.jhaago.vocalreducer.PlayerPauseRegression"], check=True)


if __name__ == "__main__":
    main()
