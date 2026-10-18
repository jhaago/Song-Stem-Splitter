# Native engine provenance and third-party notices

## demucs.cpp inference

Source: <https://github.com/sevagh/demucs.cpp>

Pinned revision: `f1206e9adeea103aef4a636b9e62297cf1f8e34e`.
Only the inference sources and headers from upstream `src/` are vendored under
`app/src/main/cpp/vendor/demucs/`. No archived demucs-android app code is included.

License: MIT, copyright (c) 2023 Sevag H. The complete license is preserved in
`app/src/main/cpp/vendor/demucs/LICENSE`.

Local changes to upstream sources:

- Guard normalization against zero variance and a one-sample denominator.
- Materialize transposed-convolution sparse expansion in blocks of at most
  2048 output rows, retaining the same GEMM, weights, precision and activation.
  This reduces temporary workspace; a full real-fixture comparison produced
  bit-identical accompaniment output.
- Check model loader reads, dimension/name bounds and dimension multiplication;
  stop cleanly at EOF. The app additionally validates all 533 expected tensor
  names, ranks, shapes and finite FP16 weights before invoking the loader.

## Eigen

Source: <https://gitlab.com/libeigen/eigen>

Pinned revision: `dd8c71e62852b2fe429edb6682ac91fd1c578a26`, matching the
upstream demucs.cpp submodule. Vendored `Eigen/` headers and the required
unsupported Tensor, FFT, MatrixFunctions and SpecialFunctions modules are under
`app/src/main/cpp/vendor/eigen/`. Tests, examples, unrelated unsupported
modules and the optional `Eigen/src/misc/lapacke.h` API header are omitted.
The supported Android and host builds do not enable LAPACKE or MKL backends.
Included headers are unmodified.

Eigen is primarily Mozilla Public License 2.0; individual files carry their
copyright/license notices. The original `COPYING.*` files, including the full
MPL2, BSD and other notices, are preserved in that directory. Compilation uses
`EIGEN_MPL2_ONLY`; Eigen's default FFT contains compatible BSD code. Source for
the included Eigen headers is available in this repository at the pinned
revision above.

## HTDemucs model

Converted model source: <https://huggingface.co/datasets/Retrobear/demucs.cpp>
(linked by the pinned demucs.cpp README).

- Dataset revision: `8f58ac0491bbea657275bcd3e38af1ab3a27bfc9`
- File: `ggml-model-htdemucs-4s-f16.bin`
- Immutable download URL: <https://huggingface.co/datasets/Retrobear/demucs.cpp/resolve/8f58ac0491bbea657275bcd3e38af1ab3a27bfc9/ggml-model-htdemucs-4s-f16.bin>
- Bytes: `83994361`
- SHA-256: `72b17c42d308982ddb5069bc3bf48b81a5aac4cb6516e4366c0fa7cef6df0064`
- Architecture: HTDemucs v4, four sources, FP16 storage / FP32 inference.
- Output source order: drums, bass, other, vocals.
- Upstream weights: <https://dl.fbaipublicfiles.com/demucs/hybrid_transformer/955717e8-8726e21a.th>
- Demucs source/license: <https://github.com/facebookresearch/demucs>, MIT,
  copyright (c) Meta Platforms, Inc. and affiliates. The complete model license
  is retained at `app/src/main/cpp/vendor/demucs/MODEL_LICENSE`.

The model is downloaded separately at runtime; no weights are included in this
repository or the APK. The checksum was verified against the downloaded bytes
in host validation. `model_manifest.hpp` records only public tensor names and
shapes, without model weights.
