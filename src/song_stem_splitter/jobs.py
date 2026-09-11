from __future__ import annotations

from dataclasses import dataclass

from .models import JobState

_ALLOWED: dict[JobState, set[JobState]] = {
    JobState.IDLE: {JobState.PREPARING},
    JobState.PREPARING: {JobState.DOWNLOADING_MODEL, JobState.SEPARATING, JobState.CANCELLED, JobState.FAILED},
    JobState.DOWNLOADING_MODEL: {JobState.SEPARATING, JobState.CANCELLED, JobState.FAILED},
    JobState.SEPARATING: {JobState.SAVING, JobState.CANCELLED, JobState.FAILED},
    JobState.SAVING: {JobState.COMPLETED, JobState.CANCELLED, JobState.FAILED},
    JobState.COMPLETED: {JobState.PREPARING},
    JobState.CANCELLED: {JobState.PREPARING},
    JobState.FAILED: {JobState.PREPARING},
}


@dataclass(slots=True)
class SeparationJobState:
    state: JobState = JobState.IDLE

    def transition(self, new_state: JobState) -> None:
        if new_state == self.state:
            return
        if new_state not in _ALLOWED[self.state]:
            raise ValueError(f"Invalid job transition: {self.state.value} -> {new_state.value}")
        self.state = new_state

    @property
    def busy(self) -> bool:
        return self.state in {
            JobState.PREPARING,
            JobState.DOWNLOADING_MODEL,
            JobState.SEPARATING,
            JobState.SAVING,
        }
