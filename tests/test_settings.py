from song_stem_splitter.settings import AppSettings, SettingsStore


def test_settings_round_trip(tmp_path):
    store = SettingsStore(tmp_path / 'settings.json')
    settings = AppSettings(output_root='/tmp/output', recent_file='/tmp/song.wav', device='cpu')
    store.save(settings)
    loaded = store.load()
    assert loaded == settings
