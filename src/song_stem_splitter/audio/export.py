from __future__ import annotations

import shutil
from pathlib import Path

from ..errors import ExportError
from ..paths import sanitize_name


def export_stem(stem, destination_directory: Path, song_name: str) -> Path:
    try:
        destination_directory.mkdir(parents=True, exist_ok=True)
        destination = destination_directory / f"{sanitize_name(song_name)} - {sanitize_name(stem.name)}.wav"
        shutil.copy2(stem.file_path, destination)
        return destination
    except OSError as exc:
        raise ExportError("The stem could not be exported.", str(exc)) from exc


def export_all(result, destination_directory: Path) -> list[Path]:
    return [export_stem(stem, destination_directory, result.source.file_path.stem) for stem in result.stems]
