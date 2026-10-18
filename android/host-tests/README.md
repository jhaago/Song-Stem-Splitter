# Host playback regression check

## Audio import end-of-file regression

```bash
python3 android/host-tests/decoder_eof_regression.py
```

Compiles the real `AudioDecoder` and `StreamingResampler` against controlled
Android boundary doubles. Checks that clear audio commits the expected PCM when
EOF flags are -1 or 0, encrypted samples at the start or later in a track remain
rejected, and empty files report no audio. Failed imports leave no output or
partial files. The original decoder misinterprets EOF -1 as encryption and fails
the clear-audio case. This does not exercise an actual device codec or provider.

## Playback scheduling

Run with JDK17 or newer:

```bash
python3 android/host-tests/player_pause_regression.py
```

This compiles the real `StemPlayer`, `MixMath`, `PcmFiles`, and `TrackSession` source against tiny temporary Android API stubs. It holds the old streaming worker inside a write while the UI performs pause/play/pause, and verifies the paused position stays at frame500. The original bug retained a flushed track clock and jumped to frame0.

This test checks generation/state ownership under a deterministic schedule. It does not test AudioTrack timing, audio focus delivery, audio quality, or actual Android lifecycle behavior. The stubs remain outside the app source sets and are never packaged.
