from pathlib import Path

from song_stem_splitter.paths import sanitize_name, song_output_directory


def test_sanitize_name_removes_invalid_path_characters():
    assert sanitize_name('A: Song? <Mix>') == 'A_ Song_ _Mix_'


def test_song_output_directory_uses_stem(tmp_path):
    result = song_output_directory(Path('/music/My Song.mp3'), tmp_path)
    assert result == tmp_path / 'My Song'
