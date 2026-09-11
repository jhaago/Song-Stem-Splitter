from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any


class JobState(str, Enum):
    IDLE = "idle"
    PREPARING = "preparing"
    DOWNLOADING_MODEL = "downloading_model"
    SEPARATING = "separating"
    SAVING = "saving"
    COMPLETED = "completed"
    CANCELLED = "cancelled"
    FAILED = "failed"


@dataclass(slots=True)
class AudioSourceInfo:
    file_path: Path
    filename: str
    duration_seconds: float | None = None
    format_name: str | None = None
    sample_rate: int | None = None
    channels: int | None = None


@dataclass(slots=True)
class StemInfo:
    id: str
    name: str
    type: str
    file_path: Path
    duration_seconds: float | None = None
    enabled: bool = True
    volume: float = 1.0


@dataclass(slots=True)
class ProcessingInfo:
    device: str
    elapsed_seconds: float
    model_downloaded_this_run: bool = False


@dataclass(slots=True)
class SeparationResult:
    source: AudioSourceInfo
    separation_model: str
    stem_mode: str
    stems: list[StemInfo]
    processing: ProcessingInfo
    output_directory: Path

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["source"]["file_path"] = str(self.source.file_path)
        data["output_directory"] = str(self.output_directory)
        for stem in data["stems"]:
            stem["file_path"] = str(stem["file_path"])
        return data


@dataclass(slots=True)
class SeparationRequest:
    input_file: Path
    stem_mode: str = "4stem"
    output_directory: Path | None = None
    device: str = "auto"
    model: str | None = None


@dataclass(slots=True)
class SeparationProgress:
    state: JobState
    message: str
    fraction: float | None = None
    detail: str | None = None


@dataclass(slots=True)
class ModelConfig:
    stem_mode: str
    model_name: str
    display_name: str
    stem_names: tuple[str, ...] = field(default_factory=tuple)
