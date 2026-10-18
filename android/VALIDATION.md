# First APK validation — 18 October 2026

## Delivered build

- App: Vocal Reducer `0.1.0-alpha1` (`versionCode 1`).
- Package: `com.jhaago.vocalreducer`.
- Android API 26 minimum / 35 target; ARM64 only.
- APK: `VocalReducer-0.1.0-alpha1-arm64.apk`, **4,154,899 bytes**.
- APK SHA-256: `709bddfcf7cbe71ea5c9a0723e3fb024c6985fe0943e01a56be0d58ed7f49935`.
- Debug certificate SHA-256: `4ed4775938ea2be6e0f4e00d17e6a4f1c00e61dc60a1c64abdec7f3a5c248532`.

## Completed checks

| Check | Observed result |
| --- | --- |
| Gradle build | `testDebugUnitTest lintDebug assembleDebug` successful |
| Android JVM tests | 23 tests, zero failures/errors/skips; one test contains 55 audio-contract assertions |
| Desktop baseline | All 14 existing Python tests pass |
| Playback scheduling regression | Real player source with controlled Android stubs preserves frame 500 across rapid pause/play/pause |
| Native CTest | Three executables pass: 11 wrapper cases, model validation, decoder allocation regression |
| APK signature | APK Signature Scheme v2 verifies |
| Package inspection | Expected package, version, min/target APIs, launcher and ARM64 native library |
| 16 KiB compatibility | APK zip alignment check passes; every native ELF LOAD segment uses `0x4000` alignment |
| Model identity | Downloaded 83,994,361 bytes match the pinned SHA-256 |
| License distribution | Full engine, model, Eigen and AndroidX notices packaged in `assets/licenses.txt` |

Lint has no errors. Remaining warnings concern the deliberate ARM64-only target,
pinned AndroidX version, English-only UI text, optional themed-icon metadata,
conservative storage checks and a worker-thread synchronous preference commit.
No lint baseline or blanket error suppression was used.

The source package omits Eigen's optional `Eigen/src/misc/lapacke.h` header.
Neither supported build enables LAPACKE or MKL. Actual ARM64 and host compiler
dependency files and fresh include-trace checks contain no references to that
header; the app and native inference sources are unchanged by this omission.

## Real native inference

The pinned upstream GSPI fixture was converted from PCM16 to float32 stereo at
44,100 Hz. These runs used the real downloaded HTDemucs weights on Linux.

| Input | Output | Peak RSS | Elapsed on this host |
| --- | --- | ---: | ---: |
| One-second fixture | 44,100 aligned frames, finite and nontrivial | 999,848 KiB | 29.49 s |
| Fixture repeated to 30 seconds | 1,323,000 aligned frames, finite | 1,207,908 KiB | 177.03 s |
| One stereo frame | Exactly eight bytes, finite | 995,568 KiB | 32.76 s |

Native cancellation during an actual encoder callback returned cancellation
and left no final or partial output. Silence and 65-second overlapping-window
behavior were tested separately at the file-wrapper boundary.

Tiling the temporary transposed-convolution expansion reduced the one-second
fixture peak from 1,752,372 KiB to 999,848 KiB (about 43%). Every output float
matched the untiled native baseline exactly. The model, weights, precision,
segment lengths and overlaps were retained. The app's 1536 MiB available-memory
preflight is based on these host observations, with margin; it cannot guarantee
survival under Android memory pressure.

## Review findings fixed

- Detached an old flushed AudioTrack before publishing a new playback generation;
  the regression reproduces the old frame-500-to-0 jump and verifies the fix.
- Synchronized cancellation and progress state updates so the stopping state
  cannot be replaced by stale progress.
- Removed Java/resource APIs unavailable on the Android 8.0 minimum.
- Persisted completed export actions so an explicit Share request can be offered
  when the user returns after backgrounding.
- Released persisted document-read grants after import/processing cleanup.

The reviewed source uses the same mixing and PCM16 conversion for playback and
export, a private FileProvider for sharing, atomic result metadata, pinned HTTPS
model download and no audio upload path. A new job preserves the previous
successful track until the replacement finishes.

## Not yet verified

No physical Android phone or usable accelerated emulator was available. Real
MediaCodec decoding, AudioTrack behavior and sound quality, file pickers,
notification permissions, rotation/backgrounding/process death, phone memory
pressure, heat/battery use and full-song processing speed remain to be tested
on a device. This is why the deliverable is a test APK and the first suggested
input is a 20–30 second clip. The host timings are not phone benchmarks.

Build and phone-test instructions are in [README.md](README.md).
