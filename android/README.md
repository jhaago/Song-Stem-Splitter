# Vocal Reducer for Android

An ad-free, phone-local vocal reducer built from the Song Stem Splitter work.
This is a **test APK**, version `0.1.0-alpha2`, for a physical-phone trial.
It is not yet a device-validated production release.

Alpha 2 fixes an import bug where the end-of-file sample flags were mistaken
for encryption, rejecting ordinary unprotected tracks with a protected-audio
message. Install it over alpha 1 and retry the same local track.

## What it does

1. Choose a local MP3, M4A or WAV using Android's file picker.
2. Prepare the track on the phone. The first run downloads an approximately
   80 MiB HTDemucs model, verifies its SHA-256 and keeps it for offline use.
3. Play the result and set the vocal level between 0 and 100. A starting level
   of 25 keeps a quiet guide vocal; 0 gives the estimated instrumental.
4. Compare with the original, then save a WAV copy or open Android's Share sheet.

No ads, accounts, analytics, subscriptions or hosted audio processing are
included. The app's only network client downloads the public model and never
receives a song path or URI. Android backup is disabled for the private audio
and model. The original file is not modified.

## First phone test

- Android 8 or later, **64-bit ARM** (`arm64-v8a`). This APK is not for a 32-bit
  Android installation, x86 emulator or iPhone.
- Open the APK on the phone and allow installation from the app used to open
  it if Android asks. It is a debug-signed development build, not a Play Store
  package. Different CI/debug signing keys can require uninstalling a previous
  test APK; uninstalling clears the downloaded model and private working audio.
- Start with a **20–30 second clip** to check speed and quality on the phone.
- Allow notifications if you want progress while the app is in the background.
  The processing job remains in a foreground service when you leave its screen.
- Wait for Ready, test 0%, 25% and 100%, seek and pause/resume, then save a copy
  and play it in another audio app.
- Test a second track while retaining the first result, then cancel. The last
  completed track should remain available. Reopening after an interrupted job
  clears incomplete private files and restores the last completed track.

The test engine currently checks for **1.5 GiB of free working memory** before
starting. A modern phone with several GB of RAM is needed; total installed RAM
is not the same as currently available memory. Separation is CPU intensive and
may take several minutes or longer. Host measurements do not establish phone
speed, thermal behavior or memory survival.

Tracks must be at most 15 minutes. Longer and unsupported inputs are rejected
explicitly. Android codec support varies by phone; stereo and mono inputs are
supported, while multichannel inputs are rejected in this build. Some vocals,
echoes and separation artifacts may remain. This version exports WAV, not MP3.

## Mixing and storage

Canonical working audio is stereo, 44,100 Hz, interleaved float32 little endian.
The engine sums drums, bass and other without independently normalizing stems.
With `g = vocal level / 100`, playback and export both use:

```text
mix = instrumental * (1 - g) + original * g
```

At 100 the result is the decoded original; at 0 it is the estimated instrumental.
Playback smooths gain changes over at most 10 ms. Both playback and WAV export
use the same PCM16 conversion and hard clipping at the output boundary. If
Original comparison is enabled, saving/sharing also uses 100; the Save button
then reads “Save original”.

Each working PCM file uses about 20.2 MiB per minute. Original and instrumental
are kept together, and one previous successful result is retained until the new
one finishes. A 15-minute track therefore needs roughly 606 MiB for a completed
working pair; preparing another similarly long track temporarily doubles that.
WAV export uses about 10.1 MiB per minute. The model adds about 80.1 MiB.
Storage checks and error paths preserve the previous complete session.

## Build

Open `android/` in Android Studio, or use Java 17 and Android SDK tools:

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The wrapper pins Gradle 8.9 with its official distribution checksum; AGP is
8.7.3. Output: `app/build/outputs/apk/debug/app-debug.apk`.
Do not commit signing keys, `local.properties`, models, PCM files or build output.
`build-android.yml` builds a test APK and runs Android/host checks in GitHub Actions.
Native libraries use 16 KiB ELF and APK alignment.

The model URL, exact revision/hash, upstream source revisions, license texts and
local inference changes are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
The APK also includes accessible license notices in About. No code was copied
from the archived GPL Android application.

## Validation boundaries

The first APK's exact hash, completed checks and remaining device tests are in
[VALIDATION.md](VALIDATION.md).

The automated checks cover mixing/export parity, PCM/WAV sizes and headers,
sample-rate conversion, finite samples, cancellation/file cleanup, model
validation, overlapping native windows and the pause/resume state regression.
See [native-tests/README.md](native-tests/README.md) and
[host-tests/README.md](host-tests/README.md) for reproducible native/state checks.

Real HTDemucs inference has been exercised on host audio. A tiled temporary
decoder workspace reduced short-fixture peak memory from about 1.67 GiB to
0.95 GiB, with bit-identical output versus the untiled native implementation.
These are Linux host observations, **not Android benchmarks**. Real Android
MediaCodec decoding, AudioTrack playback, file pickers, notification behavior,
rotation/backgrounding, device audio quality and full-track speed need the
physical-phone test. Native host inference and successful APK assembly do not
prove those behaviors.
