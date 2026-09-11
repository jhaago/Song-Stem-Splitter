from __future__ import annotations

import json
from dataclasses import dataclass, asdict
from pathlib import Path

from .paths import data_directory, default_output_root


@dataclass(slots=True)
class AppSettings:
    output_root: str = str(default_output_root())
    recent_file: str = ""
    device: str = "auto"


class SettingsStore:
    def __init__(self, path: Path | None = None):
        self.path = path or (data_directory() / "settings.json")

    def load(self) -> AppSettings:
        if not self.path.exists():
            return AppSettings()
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            return AppSettings(
                output_root=str(data.get("output_root", default_output_root())),
                recent_file=str(data.get("recent_file", "")),
                device=str(data.get("device", "auto")),
            )
        except (OSError, ValueError, TypeError):
            return AppSettings()

    def save(self, settings: AppSettings) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(asdict(settings), indent=2), encoding="utf-8")
        temp.replace(self.path)
