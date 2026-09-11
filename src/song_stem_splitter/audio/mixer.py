from __future__ import annotations

from dataclasses import dataclass


@dataclass(slots=True)
class MixerChannel:
    id: str
    name: str
    volume: float = 1.0
    muted: bool = False
    solo: bool = False


class MixerState:
    def __init__(self, channels: list[MixerChannel]):
        self.channels = {channel.id: channel for channel in channels}
        self.master_volume = 1.0

    def set_volume(self, channel_id: str, value: float) -> None:
        self.channels[channel_id].volume = max(0.0, min(1.5, float(value)))

    def set_muted(self, channel_id: str, muted: bool) -> None:
        self.channels[channel_id].muted = bool(muted)

    def set_solo(self, channel_id: str, solo: bool) -> None:
        self.channels[channel_id].solo = bool(solo)

    def effective_gain(self, channel_id: str) -> float:
        channel = self.channels[channel_id]
        any_solo = any(item.solo for item in self.channels.values())
        audible = not channel.muted and (not any_solo or channel.solo)
        return channel.volume * self.master_volume if audible else 0.0
