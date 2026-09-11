from __future__ import annotations

import shutil
import time
from pathlib import Path
from threading import Event

from ..audio.convert import prepare_for_demucs
from ..audio.metadata import probe_audio, validate_input_file
from ..errors import (
    InsufficientDiskSpaceError,
    ModelUnavailableError,
    SeparationCancelledError,
    SeparationFailedError,
)
from ..models import (
    JobState,
    ModelConfig,
    ProcessingInfo,
    SeparationProgress,
    SeparationRequest,
    SeparationResult,
    StemInfo,
)
from ..paths import song_output_directory
from .base import ProgressCallback, SeparationEngine
from .model_manager import DemucsModelManager

MODEL_CONFIGS = {
    "4stem": ModelConfig(
        stem_mode="4stem",
        model_name="htdemucs",
        display_name="4 Stems — Vocals / Drums / Bass / Other",
        stem_names=("vocals", "drums", "bass", "other"),
    ),
}


class DemucsSeparationEngine(SeparationEngine):
    def __init__(self, model_manager: DemucsModelManager | None = None):
        self.model_manager = model_manager or DemucsModelManager()

    def _emit(self, callback: ProgressCallback | None, state: JobState, message: str, fraction=None, detail=None):
        if callback:
            callback(SeparationProgress(state=state, message=message, fraction=fraction, detail=detail))

    def _resolve_device(self, requested: str) -> str:
        if requested in {"cpu", "cuda"}:
            return requested
        try:
            import torch

            if torch.cuda.is_available():
                return "cuda"
        except Exception:
            pass
        return "cpu"

    def _check_disk_space(self, input_file: Path, output_dir: Path) -> None:
        output_dir.parent.mkdir(parents=True, exist_ok=True)
        free = shutil.disk_usage(output_dir.parent).free
        required = max(512 * 1024 * 1024, input_file.stat().st_size * 20)
        if free < required:
            raise InsufficientDiskSpaceError(
                "There is not enough free disk space to separate this song.",
                f"Free={free} bytes; estimated minimum required={required} bytes",
            )

    def separate(
        self,
        request: SeparationRequest,
        progress_callback: ProgressCallback | None = None,
        cancel_event: Event | None = None,
    ) -> SeparationResult:
        started = time.monotonic()
        cancel_event = cancel_event or Event()
        validate_input_file(request.input_file)
        source_info = probe_audio(request.input_file)
        config = MODEL_CONFIGS.get(request.stem_mode)
        if config is None:
            raise SeparationFailedError("That stem mode is not available.", request.stem_mode)
        model_name = request.model or config.model_name
        if model_name != "htdemucs":
            raise ModelUnavailableError("That separation model is not configured in this build.", model_name)

        output_dir = request.output_directory or song_output_directory(request.input_file)
        self._check_disk_space(request.input_file, output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)
        self._emit(progress_callback, JobState.PREPARING, "Preparing audio and separation model…")

        if cancel_event.is_set():
            raise SeparationCancelledError("Stem separation was cancelled.")

        downloaded = False
        if not self.model_manager.is_installed(model_name):
            self._emit(progress_callback, JobState.DOWNLOADING_MODEL, "Downloading the AI separation model…")

            def model_progress(done, total, fraction):
                self._emit(
                    progress_callback,
                    JobState.DOWNLOADING_MODEL,
                    "Downloading the AI separation model…",
                    fraction,
                    f"{done} of {total} bytes" if total else f"{done} bytes downloaded",
                )

            downloaded = self.model_manager.ensure_model(model_name, model_progress, cancel_event)

        prepared = None
        try:
            self._emit(progress_callback, JobState.PREPARING, "Preparing source audio…")
            prepared = prepare_for_demucs(request.input_file, cancel_event)
            if cancel_event.is_set():
                raise SeparationCancelledError("Stem separation was cancelled.")

            device = self._resolve_device(request.device)
            self._emit(
                progress_callback,
                JobState.SEPARATING,
                f"Separating stems with {model_name} on {device.upper()}…",
                0.0,
            )

            try:
                from demucs.api import Separator, save_audio
            except Exception as exc:
                raise ModelUnavailableError(
                    "The local separation runtime is not installed correctly.",
                    f"Could not import Demucs: {exc}",
                ) from exc

            last_fraction = 0.0

            def demucs_progress(data: dict):
                nonlocal last_fraction
                if cancel_event.is_set():
                    raise KeyboardInterrupt
                audio_length = max(1, int(data.get("audio_length", 1)))
                offset = max(0, int(data.get("segment_offset", 0)))
                model_index = max(0, int(data.get("model_idx_in_bag", 0)))
                model_count = max(1, int(data.get("models", 1)))
                shift_index = max(0, int(data.get("shift_idx", 0)))
                within = min(1.0, offset / audio_length)
                fraction = min(0.94, (model_index + within) / model_count)
                if data.get("state") == "end":
                    fraction = max(fraction, last_fraction)
                last_fraction = max(last_fraction, fraction)
                self._emit(
                    progress_callback,
                    JobState.SEPARATING,
                    f"Separating stems with {model_name} on {device.upper()}…",
                    last_fraction,
                    f"Model {model_index + 1}/{model_count}, pass {shift_index + 1}",
                )

            try:
                separator = Separator(
                    model=model_name,
                    device=device,
                    shifts=1,
                    overlap=0.25,
                    split=True,
                    jobs=0,
                    progress=False,
                    callback=demucs_progress,
                )
                _, separated = separator.separate_audio_file(prepared.path)
            except KeyboardInterrupt as exc:
                raise SeparationCancelledError("Stem separation was cancelled.") from exc
            except Exception as exc:
                raise SeparationFailedError(
                    "The AI model could not separate this audio file.",
                    str(exc),
                ) from exc

            if cancel_event.is_set():
                raise SeparationCancelledError("Stem separation was cancelled.")

            self._emit(progress_callback, JobState.SAVING, "Saving generated stems…", 0.95)
            stems: list[StemInfo] = []
            ordered_names = list(config.stem_names)
            for index, name in enumerate(ordered_names):
                if cancel_event.is_set():
                    raise SeparationCancelledError("Stem separation was cancelled.")
                tensor = separated.get(name)
                if tensor is None:
                    continue
                stem_path = output_dir / f"{name}.wav"
                try:
                    save_audio(
                        tensor,
                        str(stem_path),
                        samplerate=separator.samplerate,
                        bits_per_sample=24,
                    )
                except Exception as exc:
                    raise SeparationFailedError(
                        f"The {name} stem could not be saved.",
                        str(exc),
                    ) from exc
                display = name.replace("_", " ").title()
                stems.append(
                    StemInfo(
                        id=name,
                        name=display,
                        type=name,
                        file_path=stem_path,
                        duration_seconds=source_info.duration_seconds,
                    )
                )
                self._emit(
                    progress_callback,
                    JobState.SAVING,
                    "Saving generated stems…",
                    0.95 + (0.05 * (index + 1) / len(ordered_names)),
                )

            if not stems:
                raise SeparationFailedError("No stems were generated.")

            result = SeparationResult(
                source=source_info,
                separation_model=model_name,
                stem_mode=request.stem_mode,
                stems=stems,
                processing=ProcessingInfo(
                    device=device,
                    elapsed_seconds=time.monotonic() - started,
                    model_downloaded_this_run=downloaded,
                ),
                output_directory=output_dir,
            )
            self._emit(progress_callback, JobState.COMPLETED, "Stem separation complete.", 1.0)
            return result
        finally:
            if prepared is not None:
                prepared.cleanup()
