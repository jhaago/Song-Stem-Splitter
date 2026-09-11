from __future__ import annotations

import hashlib
import os
import urllib.request
from pathlib import Path
from threading import Event

from ..errors import ModelDownloadError, SeparationCancelledError

HTDEMUCS_FILENAME = "955717e8-8726e21a.th"
HTDEMUCS_URL = "https://dl.fbaipublicfiles.com/demucs/hybrid_transformer/955717e8-8726e21a.th"
HTDEMUCS_HASH_PREFIX = "8726e21a"


class DemucsModelManager:
    def checkpoint_directory(self) -> Path:
        try:
            import torch

            return Path(torch.hub.get_dir()) / "checkpoints"
        except Exception:
            return Path.home() / ".cache" / "torch" / "hub" / "checkpoints"

    def model_path(self, model_name: str) -> Path:
        if model_name != "htdemucs":
            raise ValueError(f"No managed download metadata for model {model_name!r}")
        return self.checkpoint_directory() / HTDEMUCS_FILENAME

    def is_installed(self, model_name: str) -> bool:
        try:
            path = self.model_path(model_name)
        except ValueError:
            return False
        return path.exists() and path.stat().st_size > 1_000_000

    def ensure_model(self, model_name: str, progress=None, cancel_event: Event | None = None) -> bool:
        if self.is_installed(model_name):
            return False
        if model_name != "htdemucs":
            return False

        destination = self.model_path(model_name)
        destination.parent.mkdir(parents=True, exist_ok=True)
        partial = destination.with_suffix(destination.suffix + ".part")
        request = urllib.request.Request(HTDEMUCS_URL, headers={"User-Agent": "Song-Stem-Splitter/0.1"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response, partial.open("wb") as handle:
                total = int(response.headers.get("Content-Length", "0") or 0)
                downloaded = 0
                while True:
                    if cancel_event is not None and cancel_event.is_set():
                        raise SeparationCancelledError("Model download was cancelled.")
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
                    downloaded += len(chunk)
                    if progress is not None:
                        fraction = downloaded / total if total else None
                        progress(downloaded, total or None, fraction)
            digest = hashlib.sha256(partial.read_bytes()).hexdigest()
            if not digest.startswith(HTDEMUCS_HASH_PREFIX):
                raise ModelDownloadError(
                    "The downloaded separation model failed verification.",
                    f"Expected SHA256 prefix {HTDEMUCS_HASH_PREFIX}, got {digest[:8]}",
                )
            os.replace(partial, destination)
            return True
        except SeparationCancelledError:
            partial.unlink(missing_ok=True)
            raise
        except Exception as exc:
            partial.unlink(missing_ok=True)
            if isinstance(exc, ModelDownloadError):
                raise
            raise ModelDownloadError(
                "The separation model could not be downloaded.",
                str(exc),
            ) from exc
