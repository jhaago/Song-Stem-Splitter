from __future__ import annotations

from pathlib import Path

from ..errors import AudioDecodeError, UnsupportedInputError
from ..models import AudioSourceInfo

SUPPORTED_EXTENSIONS = {".wav", ".mp3", ".flac", ".m4a", ".aac"}


def validate_input_file(path: Path) -> None:
    if not path.exists() or not path.is_file():
        raise UnsupportedInputError("The selected audio file does not exist.", str(path))
    if path.suffix.lower() not in SUPPORTED_EXTENSIONS:
        supported = ", ".join(sorted(SUPPORTED_EXTENSIONS))
        raise UnsupportedInputError(
            "That audio format is not supported yet.",
            f"Unsupported extension {path.suffix!r}. Supported: {supported}",
        )


def probe_audio(path: Path) -> AudioSourceInfo:
    validate_input_file(path)
    duration = sample_rate = channels = None
    format_name = path.suffix.lower().lstrip(".").upper()

    try:
        import soundfile as sf

        info = sf.info(str(path))
        duration = float(info.duration)
        sample_rate = int(info.samplerate)
        channels = int(info.channels)
        format_name = info.format or format_name
    except Exception:
        try:
            from mutagen import File as MutagenFile

            audio = MutagenFile(str(path))
            if audio is None or getattr(audio, "info", None) is None:
                raise ValueError("No readable audio metadata")
            info = audio.info
            duration = float(getattr(info, "length", 0.0) or 0.0) or None
            sample_rate = int(getattr(info, "sample_rate", 0) or 0) or None
            channels = int(getattr(info, "channels", 0) or 0) or None
        except Exception as exc:
            raise AudioDecodeError(
                "The file could not be read as audio.",
                f"Metadata probe failed for {path}: {exc}",
            ) from exc

    return AudioSourceInfo(
        file_path=path,
        filename=path.name,
        duration_seconds=duration,
        format_name=format_name,
        sample_rate=sample_rate,
        channels=channels,
    )
