from __future__ import annotations

import re
from pathlib import Path

from platformdirs import user_cache_dir, user_data_dir

APP_NAME = "Song Stem Splitter"


def sanitize_name(name: str) -> str:
    cleaned = re.sub(r"[<>:\\/*?\"|]+", "_", name).strip().rstrip(".")
    cleaned = re.sub(r"\s+", " ", cleaned)
    return cleaned or "Untitled"


def default_output_root() -> Path:
    music = Path.home() / "Music"
    root = music if music.exists() else Path.home() / "Documents"
    return root / APP_NAME / "Output"


def song_output_directory(input_file: Path, output_root: Path | None = None) -> Path:
    root = output_root or default_output_root()
    return root / sanitize_name(input_file.stem)


def cache_directory() -> Path:
    return Path(user_cache_dir(APP_NAME, appauthor=False))


def data_directory() -> Path:
    return Path(user_data_dir(APP_NAME, appauthor=False))
