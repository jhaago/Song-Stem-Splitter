from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Callable
from threading import Event

from ..models import SeparationProgress, SeparationRequest, SeparationResult

ProgressCallback = Callable[[SeparationProgress], None]


class SeparationEngine(ABC):
    @abstractmethod
    def separate(
        self,
        request: SeparationRequest,
        progress_callback: ProgressCallback | None = None,
        cancel_event: Event | None = None,
    ) -> SeparationResult:
        raise NotImplementedError
