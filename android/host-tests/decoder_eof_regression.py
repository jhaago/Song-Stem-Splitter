#!/usr/bin/env python3
"""Run the real import loop against deterministic Android boundary doubles.

Reproduces getSampleFlags() == -1 at EOF without a phone or native codec.
PCM conversion, resampling, commit and error cleanup use production code.
This is not a substitute for device MediaCodec testing.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

STUBS = {
    'android/net/Uri.java': '''package android.net;
public class Uri { public String getScheme() { return "content"; } }''',
    'android/content/Context.java': '''package android.content;
public class Context { public ContentResolver getContentResolver() { return new ContentResolver(); } }''',
    'android/content/ContentResolver.java': '''package android.content;
public class ContentResolver {
 public android.content.res.AssetFileDescriptor openAssetFileDescriptor(android.net.Uri u, String mode) {
  return new android.content.res.AssetFileDescriptor();
 }
}''',
    'android/content/res/AssetFileDescriptor.java': '''package android.content.res;
public class AssetFileDescriptor implements AutoCloseable {
 public long getDeclaredLength() { return 8; }
 public long getStartOffset() { return 0; }
 public java.io.FileDescriptor getFileDescriptor() { return new java.io.FileDescriptor(); }
 public void close() { }
}''',
    'android/media/AudioFormat.java': '''package android.media;
public class AudioFormat {
 public static final int ENCODING_PCM_16BIT=2, ENCODING_PCM_8BIT=3, ENCODING_PCM_FLOAT=4;
}''',
    'android/media/MediaFormat.java': '''package android.media;
public class MediaFormat {
 public static final String KEY_MIME="mime", KEY_CHANNEL_COUNT="channels", KEY_DURATION="duration",
  KEY_SAMPLE_RATE="rate", KEY_PCM_ENCODING="encoding";
 public boolean containsKey(String key) { return !key.equals(KEY_DURATION); }
 public String getString(String key) { return "audio/raw"; }
 public int getInteger(String key) { return key.equals(KEY_SAMPLE_RATE) ? 44100 : 2; }
 public long getLong(String key) { return 0; }
}''',
    'android/media/MediaExtractor.java': '''package android.media;
import java.nio.ByteBuffer;
public class MediaExtractor {
 public static final int SAMPLE_FLAG_SYNC=1, SAMPLE_FLAG_ENCRYPTED=2, SAMPLE_FLAG_PARTIAL_FRAME=4;
 public static int sampleCount=2, encryptedIndex=-1, eofFlags=-1;
 private int position;
 public void setDataSource(java.io.FileDescriptor f, long offset, long length) { }
 public void setDataSource(java.io.FileDescriptor f) { }
 public int getTrackCount() { return 1; }
 public MediaFormat getTrackFormat(int i) { return new MediaFormat(); }
 public void selectTrack(int i) { }
 public int getSampleFlags() {
  return position >= sampleCount ? eofFlags : position == encryptedIndex ? SAMPLE_FLAG_ENCRYPTED : SAMPLE_FLAG_SYNC;
 }
 public long getSampleTime() { return position >= sampleCount ? -1 : position * 23; }
 public int readSampleData(ByteBuffer buffer, int offset) {
  if (position >= sampleCount) return -1;
  buffer.order(java.nio.ByteOrder.nativeOrder()).putShort((short)8192).putShort((short)-16384);
  return 4;
 }
 public boolean advance() { return ++position < sampleCount; }
 public void release() { }
}''',
    'android/media/MediaCodec.java': '''package android.media;
import java.nio.*;
import java.util.*;
public class MediaCodec {
 public static final int BUFFER_FLAG_END_OF_STREAM=4, BUFFER_FLAG_PARTIAL_FRAME=8,
  BUFFER_FLAG_CODEC_CONFIG=2, INFO_OUTPUT_FORMAT_CHANGED=-2;
 public static class CodecException extends IllegalStateException { }
 public static class BufferInfo { public int offset, size, flags; }
 private final ByteBuffer input=ByteBuffer.allocate(1024);
 private final Queue<byte[]> output=new ArrayDeque<>();
 private boolean eos, announced;
 private byte[] current;
 public static MediaCodec createDecoderByType(String mime) { return new MediaCodec(); }
 public void configure(MediaFormat format, Object a, Object b, int flags) { }
 public void start() { }
 public int dequeueInputBuffer(long timeout) { return 0; }
 public ByteBuffer getInputBuffer(int index) { return input; }
 public void queueInputBuffer(int index, int offset, int size, long time, int flags) {
  if ((flags & BUFFER_FLAG_END_OF_STREAM) != 0) eos=true;
  else output.add(Arrays.copyOfRange(input.array(), offset, offset+size));
 }
 public int dequeueOutputBuffer(BufferInfo info, long timeout) {
  if (!announced) { announced=true; return INFO_OUTPUT_FORMAT_CHANGED; }
  if (!output.isEmpty()) { current=output.remove(); info.size=current.length; info.flags=0; return 0; }
  if (eos) { current=new byte[0]; info.size=0; info.flags=BUFFER_FLAG_END_OF_STREAM; return 0; }
  return -1;
 }
 public MediaFormat getOutputFormat() { return new MediaFormat(); }
 public ByteBuffer getOutputBuffer(int index) { return ByteBuffer.wrap(current); }
 public void releaseOutputBuffer(int index, boolean render) { }
 public void stop() { }
 public void release() { }
}''',
}

HARNESS = r'''
package com.jhaago.vocalreducer;
import android.content.Context;
import android.media.MediaExtractor;
import android.net.Uri;
import java.io.*;
import java.nio.*;
import java.nio.file.*;

public class DecoderEofRegression {
 public static void main(String[] args) throws Exception {
  Path directory=Path.of(args[0]);
  AudioDecoder.Callback callback=new AudioDecoder.Callback() {
   public boolean isCancelled() { return false; }
   public void onProgress(float fraction, String stage) { }
  };
  for (int flags : new int[]{-1, 0}) {
   MediaExtractor.sampleCount=2; MediaExtractor.encryptedIndex=-1; MediaExtractor.eofFlags=flags;
   File file=directory.resolve("clear-"+flags+".pcm").toFile();
   long frames=AudioDecoder.decode(new Context(), new Uri(), file, callback);
   if (frames!=2 || file.length()!=16) throw new AssertionError("Clear audio did not commit both frames");
   ByteBuffer pcm=ByteBuffer.wrap(Files.readAllBytes(file.toPath())).order(ByteOrder.LITTLE_ENDIAN);
   for (int i=0; i<2; i++) {
    if (pcm.getFloat()!=0.25f || pcm.getFloat()!=-0.5f) throw new AssertionError("PCM changed");
   }
   System.out.println("PASS: clear audio imports and commits at EOF flags="+flags);
  }
  for (int encrypted : new int[]{0, 1}) {
   MediaExtractor.encryptedIndex=encrypted;
   File file=directory.resolve("encrypted-"+encrypted+".pcm").toFile();
   try {
    AudioDecoder.decode(new Context(), new Uri(), file, callback);
    throw new AssertionError("Encrypted sample was accepted");
   } catch (IOException expected) {
    if (!expected.getMessage().contains("Protected audio")) throw expected;
   }
   assertClean(file);
   System.out.println("PASS: encrypted sample "+encrypted+" rejected with incomplete file cleaned");
  }
  MediaExtractor.sampleCount=0; MediaExtractor.encryptedIndex=-1; MediaExtractor.eofFlags=-1;
  File file=directory.resolve("empty.pcm").toFile();
  try {
   AudioDecoder.decode(new Context(), new Uri(), file, callback);
   throw new AssertionError("Empty audio accepted");
  } catch (IOException expected) {
   if (!expected.getMessage().contains("no decodable audio")) throw expected;
  }
  assertClean(file);
  System.out.println("PASS: empty audio reports no audio and cleans incomplete file");
 }
 private static void assertClean(File file) {
  if (file.exists() || new File(file+".partial").exists()) throw new AssertionError("Incomplete import retained");
 }
}
'''


def main():
    root = Path(__file__).resolve().parents[1]
    java_home = os.environ.get('JAVA_HOME')
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    if not javac or not java:
        raise SystemExit('Set JAVA_HOME to a JDK (17 or newer), then retry.')
    with tempfile.TemporaryDirectory(prefix='vocal-decoder-regression-') as temp:
        work = Path(temp)
        sources = []
        for relative, content in STUBS.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(path)
        harness = work / 'com/jhaago/vocalreducer/DecoderEofRegression.java'
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_text(HARNESS)
        sources.append(harness)
        actual = root / 'app/src/main/java/com/jhaago/vocalreducer'
        sources.extend(actual / name for name in ('AudioDecoder.java', 'StreamingResampler.java'))
        classes = work / 'classes'
        subprocess.run([javac, '-d', str(classes), *map(str, sources)], check=True)
        subprocess.run([java, '-cp', str(classes), 'com.jhaago.vocalreducer.DecoderEofRegression',
                        str(work)], check=True)


if __name__ == '__main__':
    main()
