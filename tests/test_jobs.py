import pytest

from song_stem_splitter.jobs import SeparationJobState
from song_stem_splitter.models import JobState


def test_job_state_valid_lifecycle():
    job = SeparationJobState()
    for state in [JobState.PREPARING, JobState.SEPARATING, JobState.SAVING, JobState.COMPLETED]:
        job.transition(state)
    assert job.state == JobState.COMPLETED
    assert not job.busy


def test_job_state_rejects_invalid_transition():
    job = SeparationJobState()
    with pytest.raises(ValueError):
        job.transition(JobState.COMPLETED)
