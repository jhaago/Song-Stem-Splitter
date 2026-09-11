from song_stem_splitter.engine.demucs_engine import MODEL_CONFIGS


def test_four_stem_mode_is_data_driven():
    config = MODEL_CONFIGS['4stem']
    assert config.model_name == 'htdemucs'
    assert config.stem_names == ('vocals', 'drums', 'bass', 'other')


def test_stem_names_are_defined_by_model_configuration():
    assert all(config.stem_names for config in MODEL_CONFIGS.values())
    assert all(len(set(config.stem_names)) == len(config.stem_names) for config in MODEL_CONFIGS.values())
