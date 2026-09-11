from pathlib import Path

from song_stem_splitter.models import AudioSourceInfo, ProcessingInfo, SeparationResult, StemInfo


def test_separation_result_serializes_paths():
    result = SeparationResult(
        source=AudioSourceInfo(Path('/tmp/song.wav'), 'song.wav'),
        separation_model='htdemucs',
        stem_mode='4stem',
        stems=[StemInfo('vocals', 'Vocals', 'vocals', Path('/tmp/vocals.wav'))],
        processing=ProcessingInfo(device='cpu', elapsed_seconds=1.2),
        output_directory=Path('/tmp/output'),
    )
    data = result.to_dict()
    assert data['source']['file_path'] == '/tmp/song.wav'
    assert data['stems'][0]['file_path'] == '/tmp/vocals.wav'
