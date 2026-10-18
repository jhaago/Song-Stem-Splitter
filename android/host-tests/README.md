# Host playback regression check

Run with JDK17 or newer:

```bash
python3 android/host-tests/player_pause_regression.py
```

This compiles the real `StemPlayer`, `MixMath`, `PcmFiles`, and `TrackSession` source against tiny temporary Android API stubs. It holds the old streaming worker inside a write while the UI performs pause/play/pause, and verifies the paused position stays at frame500. The original bug retained a flushed track clock and jumped to frame0.

This test checks generation/state ownership under a deterministic schedule. It does not test AudioTrack timing, audio focus delivery, audio quality, or actual Android lifecycle behavior. The stubs remain outside the app source sets and are never packaged.
