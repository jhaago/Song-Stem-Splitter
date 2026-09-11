# Song Stem Splitter

Song Stem Splitter is a local-first desktop utility for separating a normal mixed song into synchronized musical stems. The initial build targets Windows and macOS and produces four stems: **Vocals, Drums, Bass and Other**.

**Development status:** `v0.1.0-alpha.1` foundation/MVP. The application shell, local separation engine boundary, Demucs integration, synchronized mixer transport, WAV export, CLI, tests and desktop build recipe are implemented. Real-world separation and packaged-app validation should still be performed on target Windows/macOS hardware before calling this production-ready.

## Architecture

```text
Desktop GUI (PySide6)
        |
        v
Song Stem Splitter engine interface
        |
        +---- Demucs adapter (current implementation)
        |       |
        |       v
        |    HTDemucs model
        |
        +---- Future alternative separator

CLI -----------^

Future KidsChurch Presenter integration ----> same engine boundary
```

The GUI never constructs Demucs command-line arguments or handles model tensors. It creates a `SeparationRequest` and calls the `SeparationEngine` abstraction. This is intentional: KidsChurch Presenter can later call the same engine or a thin packaged CLI without taking a dependency on the desktop GUI.

## Why HTDemucs

The MVP uses the maintained Demucs 4.1 package with the `htdemucs` model because it is a proven local separator, provides the required vocals/drums/bass/other output, has an MIT-licensed codebase, runs on CPU and supports CUDA through PyTorch, and exposes an API callback that provides real chunk-level processing progress. The experimental six-source model is deliberately not enabled in the MVP because its piano quality is documented as weaker.

The model weights are downloaded on first use only, stored in the normal PyTorch cache and SHA256-prefix verified by the app before use. The UI informs the user before that first download.

## Requirements

- Python 3.10–3.13 for source/development use (Python 3.11 is the packaging target)
- Windows 10/11 or a current supported macOS release
- Internet connection once to download the HTDemucs model weights
- Enough free disk space for decoded source audio and generated WAV stems
- CUDA-compatible PyTorch installation if NVIDIA GPU acceleration is desired; otherwise CPU is used

The normal application workflow is local after the model has been cached. Songs are not uploaded to a service.

## Development setup

```bash
python -m venv .venv
```

Activate the environment, then:

```bash
python -m pip install --upgrade pip
pip install -e ".[app,dev]"
stem-separator
```

The package includes `imageio-ffmpeg`; compressed inputs are decoded to a temporary stereo 44.1 kHz WAV in the application cache before separation. That keeps MP3/FLAC/M4A/AAC handling consistent across platforms and avoids scattering temporary files through the user's music folders.

## Engine CLI

The GUI and CLI call the same Python engine implementation.

```bash
stem-separator-engine separate input.mp3 --mode 4stem --output ./Output/MySong
stem-separator-engine separate input.mp3 --device cpu --json
stem-separator-engine model-status
```

Current CLI/device values are `auto`, `cpu` and `cuda`.

## Output layout

The default structure is:

```text
Music/
  Song Stem Splitter/
    Output/
      Song Name/
        vocals.wav
        drums.wav
        bass.wav
        other.wav
```

The Results screen can also export all or individual stems with friendly names such as `Song Name - Vocals.wav`.

## Synchronized playback

Playback uses one `sounddevice` output stream and one shared frame counter. Every stem is read at the same position inside the same callback and mixed there. Mute, solo and volume change gain only; they do not restart a stem or give each stem an independent media timeline.

This is intentionally different from implementing four unrelated audio players, which can drift over time.

## Model and processing behaviour

The application currently supports:

- `4stem` -> `htdemucs`
- 24-bit WAV stem output
- chunk-level separation progress when Demucs exposes it
- elapsed time display
- CPU processing everywhere
- CUDA when available and requested/auto-selected
- cancellation during download and Demucs chunk processing
- a single active separation job

Model-specific details live under `src/song_stem_splitter/engine/` rather than in GUI code.

## Tests

Fast tests do not require physical audio hardware or the model download:

```bash
pip install -e ".[dev]"
pytest
```

They cover path generation, settings persistence, file validation, result metadata serialization, separation job state transitions and mixer mute/solo/master-gain logic.

### Manual separation validation

Before a release, validate at least one WAV and one compressed song on both Windows and macOS:

1. Launch `stem-separator`.
2. Drag or select a song.
3. Confirm duration/format metadata is displayed.
4. Select **4 Stems** and press **Separate Stems**.
5. On first run, confirm the model-download warning appears and download progress is displayed.
6. Confirm processing does not freeze the UI and progress/elapsed time update.
7. Play all four stems together and seek repeatedly.
8. Mute/unmute while playing and confirm the timeline does not jump.
9. Solo each stem and confirm only soloed channels are audible.
10. Change individual and master volume while playing.
11. Export one stem and all stems; verify the resulting WAV files open normally.
12. Open the output folder from the application.
13. Repeat once on CPU; where available, repeat with CUDA.
14. Cancel one separation mid-run and confirm the GUI returns to a usable state.

## Build Windows/macOS desktop bundles

Install full build dependencies:

```bash
pip install -e ".[app,dev]"
pyinstaller --clean song_stem_splitter.spec
```

The output is under `dist/`. The repository also contains a manually triggered GitHub Actions workflow that builds Windows and macOS PyInstaller bundles.

PyInstaller bundles containing PyTorch/Demucs are large. A later distribution pass should add platform-native signing/notarization and installer wrappers (for example DMG/PKG on macOS and MSIX/Inno Setup on Windows) after the core application has been validated.

## Important implementation boundaries

### GUI

Responsible for user workflow, file selection, progress display, transport controls, mixer controls and export interaction.

### Separation Engine

Responsible for input validation, model management, device choice, decode preparation, separation, cancellation, output naming and structured result metadata.

### AI Model

Demucs/HTDemucs is an implementation detail behind the engine adapter. The rest of the application should not assume the separator will always be Demucs.

## Known limitations

- Only the standard four-stem mode is exposed in the GUI.
- CUDA is the only GPU path intentionally auto-selected in the MVP; Apple MPS is not enabled until it is validated with the packaged application.
- The player currently performs disk reads inside the PortAudio callback. This avoids full-song RAM copies and works as a straightforward MVP, but a buffered audio engine is a worthwhile next refinement for slower disks/very high system load.
- Model download cancellation happens between network chunks; model construction itself is not instantly interruptible.
- The desktop bundles are not yet code-signed/notarized and are not native installer packages.
- The model is not bundled with the application; the first use needs internet access.
- Actual AI quality and processing speed vary with source material and hardware.

## Recommended next phase

After physical validation of this build, the next development phase should focus on distribution hardening rather than feature expansion: packaged-app testing on Intel/Apple Silicon macOS and Windows, native installers/signing, improved buffered playback, robust GPU selection/fallback, model-cache settings, and a small integration-oriented engine service/IPC boundary for KidsChurch Presenter. Only after that should 2-stem/6-stem modes and richer audio tools be added.
