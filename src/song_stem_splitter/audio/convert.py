from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path

from ..errors import AudioDecodeError, SeparationCancelledError
from ..paths import cache_directory


class PreparedAudio:
    def __init__(self, path: Path, temporary: bool = False):
        self.path = path
        self.temporary = temporary

    def cleanup(self) -> None:
        if self.temporary:
            try:
                self.path.unlink(missing_ok=True)
                self.path.parent.rmdir()
            except OSError:
                pass


def prepare_for_demucs(input_file: Path, cancel_event=None) -> PreparedAudio:
    if input_file.suffix.lower() == ".wav":
        return PreparedAudio(input_file, temporary=False)

    if cancel_event is not None and cancel_event.is_set():
        raise SeparationCancelledError("Stem separation was cancelled.")

    try:
        import imageio_ffmpeg

        ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    except Exception as exc:
        raise AudioDecodeError(
            "The bundled audio decoder is unavailable.",
            f"Could not locate imageio-ffmpeg: {exc}",
        ) from exc

    cache_directory().mkdir(parents=True, exist_ok=True)
    temp_dir = Path(tempfile.mkdtemp(prefix="decode-", dir=cache_directory()))
    output = temp_dir / "source.wav"
    command = [
        ffmpeg,
        "-nostdin",
        "-y",
        "-v",
        "error",
        "-i",
        str(input_file),
        "-vn",
        "-ac",
        "2",
        "-ar",
        "44100",
        "-c:a",
        "pcm_s24le",
        str(output),
    ]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, check=False)
    except OSError as exc:
        raise AudioDecodeError("The audio decoder could not be started.", str(exc)) from exc

    if completed.returncode != 0 or not output.exists():
        detail = (completed.stderr or completed.stdout or "Unknown FFmpeg error").strip()
        raise AudioDecodeError("The selected audio file could not be decoded.", detail)

    if cancel_event is not None and cancel_event.is_set():
        output.unlink(missing_ok=True)
        raise SeparationCancelledError("Stem separation was cancelled.")

    return PreparedAudio(output, temporary=True)
