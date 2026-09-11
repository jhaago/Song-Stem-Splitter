import pytest

from song_stem_splitter.audio.metadata import validate_input_file
from song_stem_splitter.errors import UnsupportedInputError


def test_validation_rejects_unknown_extension(tmp_path):
    path = tmp_path / 'song.xyz'
    path.write_bytes(b'not audio')
    with pytest.raises(UnsupportedInputError):
        validate_input_file(path)


def test_validation_accepts_supported_extension(tmp_path):
    path = tmp_path / 'song.wav'
    path.write_bytes(b'placeholder')
    validate_input_file(path)
