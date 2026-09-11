from __future__ import annotations

import logging
from logging.handlers import RotatingFileHandler

from .paths import data_directory


def configure_logging() -> None:
    log_dir = data_directory() / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    log_file = log_dir / "song-stem-splitter.log"
    root = logging.getLogger()
    if any(isinstance(handler, RotatingFileHandler) for handler in root.handlers):
        return
    root.setLevel(logging.INFO)
    handler = RotatingFileHandler(log_file, maxBytes=2_000_000, backupCount=3, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    root.addHandler(handler)
