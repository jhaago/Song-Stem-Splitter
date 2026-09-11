from __future__ import annotations

import threading
from pathlib import Path

from .mixer import MixerChannel, MixerState


class SynchronizedStemPlayer:
    """One transport and one audio callback for every stem, preventing player drift."""

    def __init__(self, stems):
        self._stems = list(stems)
        self.mixer = MixerState([MixerChannel(s.id, s.name, volume=s.volume) for s in self._stems])
        self._lock = threading.RLock()
        self._files = {}
        self._stream = None
        self._playing = False
        self._position_frames = 0
        self._pending_seek: int | None = None
        self._sample_rate = 44100
        self._channels = 2
        self._total_frames = 0
        self._open_files()

    def _open_files(self) -> None:
        import soundfile as sf

        sample_rates = set()
        frame_counts = []
        for stem in self._stems:
            handle = sf.SoundFile(str(Path(stem.file_path)), mode="r")
            self._files[stem.id] = handle
            sample_rates.add(handle.samplerate)
            frame_counts.append(len(handle))
        if len(sample_rates) != 1:
            self.close()
            raise ValueError("Stem sample rates do not match")
        if not frame_counts:
            raise ValueError("No stems were provided")
        self._sample_rate = sample_rates.pop()
        self._total_frames = min(frame_counts)

    @property
    def duration_seconds(self) -> float:
        return self._total_frames / self._sample_rate if self._sample_rate else 0.0

    @property
    def position_seconds(self) -> float:
        with self._lock:
            return self._position_frames / self._sample_rate

    @property
    def playing(self) -> bool:
        return self._playing

    def _ensure_stream(self) -> None:
        if self._stream is not None:
            return
        import sounddevice as sd

        self._stream = sd.OutputStream(
            samplerate=self._sample_rate,
            channels=self._channels,
            dtype="float32",
            blocksize=1024,
            callback=self._callback,
        )
        self._stream.start()

    def play(self) -> None:
        self._ensure_stream()
        self._playing = True

    def pause(self) -> None:
        self._playing = False

    def stop(self) -> None:
        self._playing = False
        self.seek(0.0)

    def seek(self, seconds: float) -> None:
        frame = int(max(0.0, min(self.duration_seconds, seconds)) * self._sample_rate)
        with self._lock:
            self._pending_seek = frame
            self._position_frames = frame
        if self._stream is None:
            for handle in self._files.values():
                handle.seek(frame)
            self._pending_seek = None

    def _callback(self, outdata, frames, time_info, status) -> None:
        import numpy as np

        with self._lock:
            if self._pending_seek is not None:
                target = self._pending_seek
                for handle in self._files.values():
                    handle.seek(target)
                self._position_frames = target
                self._pending_seek = None

            if not self._playing or self._position_frames >= self._total_frames:
                outdata.fill(0)
                if self._position_frames >= self._total_frames:
                    self._playing = False
                return

            mixed = np.zeros((frames, self._channels), dtype=np.float32)
            read_frames = frames
            for stem in self._stems:
                handle = self._files[stem.id]
                block = handle.read(frames, dtype="float32", always_2d=True)
                read_frames = min(read_frames, len(block))
                if block.shape[1] == 1:
                    block = np.repeat(block, 2, axis=1)
                elif block.shape[1] > 2:
                    block = block[:, :2]
                gain = self.mixer.effective_gain(stem.id)
                if gain:
                    mixed[: len(block)] += block * gain

            outdata.fill(0)
            if read_frames:
                outdata[:read_frames] = np.clip(mixed[:read_frames], -1.0, 1.0)
            self._position_frames += read_frames
            if read_frames < frames or self._position_frames >= self._total_frames:
                self._playing = False

    def close(self) -> None:
        self._playing = False
        if self._stream is not None:
            try:
                self._stream.stop()
                self._stream.close()
            except Exception:
                pass
            self._stream = None
        for handle in self._files.values():
            try:
                handle.close()
            except Exception:
                pass
        self._files.clear()
