# Android Vocal Reducer — accepted first-build design

## Purpose and authorization

Jordan wants an easy, ad-free Android app to remove or reduce vocals in tracks using the existing Song Stem Splitter work. The conversation approved an installable test APK: choose local audio, process once on the phone, adjust a vocal slider, compare with the original, and save/share the audible mix. The user said “Let's do it” on 18 October 2026. Proceed with this implementation; the remaining uncertainty is device speed and separation quality, not authorization to build.

## First-build experience

- App name: Vocal Reducer. Package: `com.jhaago.vocalreducer`.
- Native Android app, minimum Android 8 (API 26), targeting API 35, ARM64 device build.
- Pick a local audio file through Android's document picker; accept formats Android's decoder supports, including MP3, M4A and WAV. Handle unsupported/corrupt input as an actionable error.
- Download a pinned, SHA-256-verified HTDemucs four-source model once over HTTPS. Songs stay on the phone. No accounts, ads, analytics or remote processing.
- Convert to stereo, 44,100 Hz, interleaved little-endian float32 PCM in private storage. Bound temporary disk use and reject overlong input explicitly; first build supports up to 15 minutes without silently trimming.
- Process one job at a time with progress, elapsed time, cancellation and a foreground notification. Keep native working memory bounded with overlapping windows.
- Retain aligned original and instrumental PCM files. The instrumental is the sum of Demucs drums, bass and other, without independent normalization.
- Vocal slider 0–100 (default 25). Mix is `instrumental * (1-g) + original * g`, where `g=level/100`. 0 means instrumental; 100 means the decoded original. Smooth level changes in playback.
- Preview uses one AudioTrack and a common sample position. Play/pause, seek, Original comparison, elapsed/duration. Changing the slider does not rerun separation.
- Save copy via Android's create-document picker as a stereo 44.1 kHz PCM16 WAV. Share uses a content URI with temporary read permission. Preview and export use the same mixing and clipping policy. Original source files are never overwritten automatically.
- WAV is the first-build export format; compressed MP3 export is outside this test APK.
- Persist the most recent successfully processed track and its settings. Incomplete jobs are cleaned up and safely retryable. Configuration changes/backgrounding must not destroy processing.

## Architecture

Keep the desktop application untouched. Add `android/` to the existing repository on `feature/android-vocal-reducer`.

- Java Android application with platform views and APIs, no UI framework dependency required.
- A foreground service owns decode/download/native processing; an Activity binds to its current state.
- `NativeSeparator` is the sole Java/native engine bridge. C++ uses a pinned MIT-licensed `demucs.cpp` implementation and pinned Eigen headers; preserve license notices and provenance. The archived Android reference is informational; do not copy its GPL app code.
- Canonical PCM files are the boundary between decoder, native separation, player and exporter.
- Pure Java DSP/file helpers have JVM tests; native validation/tests run on the host. Build and inspect ARM64 APK. Physical phone performance remains explicitly unverified until Jordan installs it.

## Native interface contract

Java class `com.jhaago.vocalreducer.NativeSeparator` loads `vocal_engine`.

```
static native void separate(String modelPath, String sourcePcmPath,
    String instrumentalPcmPath, long frameCount, ProgressCallback callback);
static native void cancel();
interface ProgressCallback { void onProgress(float fraction, String stage); }
```

`separate` reads exactly `frameCount` stereo frames, 8 bytes per frame, little-endian float32 at 44.1 kHz. It writes exactly the same frame count to a temporary output and atomically promotes it on success. On cancellation/error throw a Java exception and leave no completed output. Nonfinite audio is rejected, silence is handled without divide-by-zero, all tensor sizes/model load failures are checked. Progress is monotonic 0–1. Cancellation can be checked at native model callback boundaries.

## Validation and practical limits

Meaningful checks: slider endpoints/intermediate mix, export/preview parity, file headers/frame alignment, resampling continuity and silence, corrupt/truncated input, cancellation cleanup, verified model download, native model execution on a real audio fixture, Java unit suite, desktop baseline suite and APK assembly/signature/manifest/native ABI verification. Do not claim listening quality or phone benchmarks from host tests.

## Sources

- Existing desktop engine: `src/song_stem_splitter/engine/demucs_engine.py`.
- Native inference reference: https://github.com/sevagh/demucs.cpp at `f1206e9adeea103aef4a636b9e62297cf1f8e34e` (MIT).
- Android feasibility reference: https://github.com/sevagh/demucs-android (archived; code not copied).
