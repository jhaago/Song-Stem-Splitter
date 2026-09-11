from song_stem_splitter.jobs import SeparationJobState
from song_stem_splitter.models import JobState


def test_job_can_cancel_while_preparing_and_restart():
    job = SeparationJobState()
    job.transition(JobState.PREPARING)
    assert job.busy
    job.transition(JobState.CANCELLED)
    assert job.state == JobState.CANCELLED
    assert not job.busy
    job.transition(JobState.PREPARING)
    assert job.busy
