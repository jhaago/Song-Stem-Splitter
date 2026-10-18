# Native validation

Build with CMake 3.22+ and a C++17 compiler:

```sh
cmake -S android/app/src/main/cpp -B /tmp/vocal-host-build -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/vocal-host-build -j2
ctest --test-dir /tmp/vocal-host-build --output-on-failure
```

`vocal_wrapper_tests` verifies file validation, exact frame counts, silent/short
input, gain-preserving overlapping windows, monotonic progress and cancellation
cleanup using a deliberately simple backend. It tests the file/window boundary,
not HTDemucs math. `vocal_convolution_memory` guards against materializing a
full sparse decoder expansion (less than 96MiB peak RSS in its isolated case);
`vocal_model_validation` rejects truncated, wrong-magic and unbounded metadata
before model tensor allocation.

A real model run can be invoked as:

```sh
/tmp/vocal-host-build/vocal_inference MODEL.bin INPUT_FLOAT32_STEREO_LE.pcm OUTPUT.pcm FRAMES
```

The model's pinned URL, expected size and checksum are in
`android/THIRD_PARTY_NOTICES.md`. Both input and output are 44,100Hz interleaved
stereo float32 little endian, without a header; each frame occupies eight bytes.
Output must not already exist. Cancellation/error removes `OUTPUT.pcm.part`.
The source file and an existing completed output are preserved.

For a known real fixture, demucs.cpp at its pinned revision contains
`test/data/gspi_stereo_short.wav` (PCM16, stereo, 44,100Hz, 44,100 frames). Convert
samples to float32 by division by 32768. The fixture remains upstream rather
than duplicating its audio into this repository.

Host inference and an ARM64 cross-build do not establish phone performance,
memory survival, audio playback quality or end-to-end Android behavior.
