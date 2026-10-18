# Android Vocal Reducer Implementation Plan

> **For agentic workers:** Execute the scoped tasks with independent ownership; use test-driven-development for core audio behavior and verification-before-completion for claims.

**Goal:** Deliver an installable ad-free Android test APK with local vocal separation, an adjustable vocal level and WAV save/share.

**Architecture:** Platform Java UI/service/audio decoder and player surround an MIT C++ HTDemucs engine. Shared stereo 44.1 kHz float PCM files keep decoding, inference, preview and export aligned. Work occurs in a dedicated clone/feature branch; no main-branch changes.

**Tech Stack:** Java 17, Android API 35/min26, Gradle 8.9/AGP 8.7.3, NDK 27, C++17, demucs.cpp and Eigen.

**Spec:** `docs/superpowers/specs/2026-10-18-android-vocal-reducer.md`

## Global Constraints

- Package `com.jhaago.vocalreducer`, app `Vocal Reducer`, min26/target35, ARM64 APK.
- One job; local processing; no ads/accounts/analytics; model only is downloaded.
- PCM boundary: little-endian float32, stereo, 44,100 Hz, 8 bytes/frame.
- Mix `I*(1-g)+X*g`, slider 0–100; identical preview/export clipping to [-1,1] before PCM16 conversion.
- Maximum input 15 minutes; explicit rejection, no silent truncation.
- Never overwrite input; output created through SAF or shared cache provider.

## Review Focus

1. Activity recreation/backgrounding during model download or inference must preserve the service-owned job.
2. Cancellation/error must remove partial results while preserving the last valid session.
3. Very short, silent, mono, non-44.1 kHz and corrupt inputs must not crash native inference or produce nonfinite samples.
4. Vocal adjustment, Original compare and export must use one aligned timeline and consistent gains.
5. A first-run model download must be verified and retryable, and no song bytes may be sent to the network.

## Task 1: Native separation engine

**Owner/files:** native implementer owns `android/app/src/main/cpp/`, `android/native-tests/`, engine provenance/license notices. Root owns Java declaration and app Gradle.

**Interfaces:** Consume the NativeSeparator contract in the spec; produce `libvocal_engine.so` through CMake and a host-testable engine function with identical file semantics.

- [x] Pin/vendor demucs.cpp and Eigen; keep only needed inference source/header dependencies and licenses.
- [x] Write native tests for input validation, silent audio and output frame count/cancellation boundaries; run expected failures before wrapper implementation.
- [x] Implement model load, bounded-window inference, aligned accompaniment output, monotonic progress and callback-driven cancellation.
- [x] Compile host tests; run model inference on a short real audio fixture and inspect finite output/frame count.
- [x] Record exact model source/revision/SHA-256 for the Android downloader; document any relevant limitations.

## Task 2: Android workflow and audio

**Owner/files:** root owns `android/*.gradle`, wrapper/manifest/resources, Java classes under `android/app/src/main/java/com/jhaago/vocalreducer/`, and Java tests.

**Interfaces:** Decoder produces canonical PCM and frame count. Service invokes NativeSeparator. Player and exporter consume aligned source/instrumental PCM. Activity observes service state.

- [x] Write/execute failing pure-Java tests for mix endpoints/intermediate sample values, PCM16 WAV writer, resampling and filename/file-length validation.
- [x] Implement tested audio helpers, decoder and one-stream player with click-free level updates.
- [x] Implement verified model cache, foreground processing service, cancellation, saved session and recovery.
- [x] Implement clear phone UI, file picker, progress, playback, vocal slider, compare, Save/Share and error messages.
- [x] Run Java tests and Android lint/compile; keep API/resource use compatible with min26.

## Task 3: Build, review and deliver

**Owner/files:** root owns integration, build docs and reproducible Android CI. Independent reviewer reads complete Android diff.

- [x] Run existing desktop baseline tests without installing heavy inference dependencies.
- [x] Build APK, verify APK signature, package/SDK/permissions and ARM64 native library alignment.
- [x] Run host engine test with model and sample audio; report host results separately from untested phone performance.
- [x] Independent review for lifecycle, audio correctness, network/file safety and actual end-to-end wiring. Fix material findings and repeat affected checks.
- [ ] Persist source in feature branch/PR as authorized, save APK as a user-facing file, and provide concise install/use instructions with explicit test-build limits.
