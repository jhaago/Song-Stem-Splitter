from song_stem_splitter.audio.mixer import MixerChannel, MixerState


def test_solo_mutes_non_solo_channels_without_changing_timeline_state():
    state = MixerState([MixerChannel('vocals', 'Vocals'), MixerChannel('drums', 'Drums')])
    state.set_solo('vocals', True)
    assert state.effective_gain('vocals') == 1.0
    assert state.effective_gain('drums') == 0.0


def test_mute_wins_over_solo():
    state = MixerState([MixerChannel('vocals', 'Vocals')])
    state.set_solo('vocals', True)
    state.set_muted('vocals', True)
    assert state.effective_gain('vocals') == 0.0


def test_master_volume_is_applied():
    state = MixerState([MixerChannel('bass', 'Bass', volume=0.8)])
    state.master_volume = 0.5
    assert state.effective_gain('bass') == 0.4
