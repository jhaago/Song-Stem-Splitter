from __future__ import annotations

import logging
import threading
import time
from pathlib import Path

from PySide6.QtCore import QObject, QThread, QTimer, Qt, Signal, Slot, QUrl
from PySide6.QtGui import QDesktopServices, QDragEnterEvent, QDropEvent
from PySide6.QtWidgets import (
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QProgressBar,
    QSlider,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
    QComboBox,
)

from ..audio.export import export_all, export_stem
from ..audio.metadata import probe_audio
from ..audio.player import SynchronizedStemPlayer
from ..engine.demucs_engine import DemucsSeparationEngine
from ..errors import SeparationCancelledError, StemSplitterError
from ..models import SeparationRequest
from ..paths import song_output_directory
from ..settings import SettingsStore

logger = logging.getLogger(__name__)

DARK_STYLE = """
QWidget { background: #17191d; color: #e9ecf1; font-size: 13px; }
QMainWindow { background: #111318; }
QFrame#dropZone { border: 2px dashed #596273; border-radius: 12px; background: #1f232a; }
QFrame#panel { background: #1d2026; border: 1px solid #303640; border-radius: 10px; }
QPushButton { background: #2f6fed; border: 0; border-radius: 6px; padding: 8px 14px; font-weight: 600; }
QPushButton:hover { background: #3b7cff; }
QPushButton:disabled { background: #3b414c; color: #8c929d; }
QPushButton#secondary { background: #303640; }
QPushButton#danger { background: #8f3541; }
QComboBox { background: #252932; padding: 7px; border: 1px solid #3a414d; border-radius: 5px; }
QProgressBar { background: #252932; border: 0; border-radius: 5px; height: 10px; text-align: center; }
QProgressBar::chunk { background: #2f6fed; border-radius: 5px; }
QSlider::groove:horizontal { height: 5px; background: #353b46; }
QSlider::handle:horizontal { width: 14px; margin: -5px 0; border-radius: 7px; background: #d6dbe4; }
"""


def _format_time(value: float) -> str:
    value = max(0, int(value))
    return f"{value // 60}:{value % 60:02d}"


class SeparationWorker(QObject):
    progress = Signal(object)
    finished = Signal(object)
    failed = Signal(object)

    def __init__(self, request: SeparationRequest):
        super().__init__()
        self.request = request
        self.cancel_event = threading.Event()

    @Slot()
    def run(self):
        engine = DemucsSeparationEngine()
        try:
            result = engine.separate(self.request, self.progress.emit, self.cancel_event)
            self.finished.emit(result)
        except Exception as exc:
            self.failed.emit(exc)

    def cancel(self):
        self.cancel_event.set()


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Song Stem Splitter")
        self.resize(980, 720)
        self.setAcceptDrops(True)
        self.setStyleSheet(DARK_STYLE)
        self.settings_store = SettingsStore()
        self.settings = self.settings_store.load()
        self.current_file: Path | None = None
        self.current_result = None
        self.player: SynchronizedStemPlayer | None = None
        self.worker = None
        self.worker_thread = None
        self.processing_started = 0.0

        self.stack = QStackedWidget()
        self.setCentralWidget(self.stack)
        self.start_page = self._build_start_page()
        self.process_page = self._build_process_page()
        self.results_page = self._build_results_page()
        self.stack.addWidget(self.start_page)
        self.stack.addWidget(self.process_page)
        self.stack.addWidget(self.results_page)
        self.stack.setCurrentWidget(self.start_page)

        self.elapsed_timer = QTimer(self)
        self.elapsed_timer.timeout.connect(self._update_elapsed)
        self.transport_timer = QTimer(self)
        self.transport_timer.setInterval(100)
        self.transport_timer.timeout.connect(self._update_transport)

    def _build_start_page(self):
        page = QWidget()
        outer = QVBoxLayout(page)
        outer.setContentsMargins(48, 40, 48, 40)
        title = QLabel("Song Stem Splitter")
        title.setStyleSheet("font-size: 30px; font-weight: 700;")
        subtitle = QLabel("Local AI-assisted stem separation — no account or cloud upload required")
        subtitle.setStyleSheet("color: #9da5b4; font-size: 15px;")
        outer.addWidget(title)
        outer.addWidget(subtitle)
        outer.addSpacing(30)

        drop = QFrame()
        drop.setObjectName("dropZone")
        drop_layout = QVBoxLayout(drop)
        drop_layout.setAlignment(Qt.AlignCenter)
        hint = QLabel("Drop a song here")
        hint.setStyleSheet("font-size: 22px; font-weight: 600;")
        formats = QLabel("WAV · MP3 · FLAC · M4A · AAC")
        formats.setStyleSheet("color: #8f97a6;")
        choose = QPushButton("Select Audio File")
        choose.clicked.connect(self._select_file)
        choose.setFixedWidth(190)
        drop_layout.addWidget(hint, alignment=Qt.AlignCenter)
        drop_layout.addWidget(formats, alignment=Qt.AlignCenter)
        drop_layout.addSpacing(12)
        drop_layout.addWidget(choose, alignment=Qt.AlignCenter)
        outer.addWidget(drop, stretch=1)

        recent = Path(self.settings.recent_file) if self.settings.recent_file else None
        self.recent_button = QPushButton()
        self.recent_button.setObjectName("secondary")
        if recent and recent.exists():
            self.recent_button.setText(f"Recent: {recent.name}")
            self.recent_button.clicked.connect(lambda: self._load_file(recent))
            outer.addWidget(self.recent_button)
        return page

    def _build_process_page(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(48, 36, 48, 36)
        heading = QLabel("Prepare Separation")
        heading.setStyleSheet("font-size: 26px; font-weight: 700;")
        layout.addWidget(heading)

        panel = QFrame()
        panel.setObjectName("panel")
        panel_layout = QVBoxLayout(panel)
        self.file_label = QLabel("No file selected")
        self.file_label.setStyleSheet("font-size: 17px; font-weight: 600;")
        self.info_label = QLabel("")
        self.info_label.setStyleSheet("color: #9da5b4;")
        panel_layout.addWidget(self.file_label)
        panel_layout.addWidget(self.info_label)
        layout.addWidget(panel)
        layout.addSpacing(16)

        layout.addWidget(QLabel("Separation mode"))
        self.mode_combo = QComboBox()
        self.mode_combo.addItem("4 Stems — Vocals / Drums / Bass / Other", "4stem")
        layout.addWidget(self.mode_combo)

        self.output_label = QLabel("")
        self.output_label.setStyleSheet("color: #9da5b4;")
        layout.addWidget(self.output_label)

        self.status_label = QLabel("")
        self.progress = QProgressBar()
        self.progress.setRange(0, 100)
        self.progress.setValue(0)
        self.elapsed_label = QLabel("")
        layout.addWidget(self.status_label)
        layout.addWidget(self.progress)
        layout.addWidget(self.elapsed_label)

        buttons = QHBoxLayout()
        self.back_button = QPushButton("Choose Different File")
        self.back_button.setObjectName("secondary")
        self.back_button.clicked.connect(lambda: self.stack.setCurrentWidget(self.start_page))
        self.separate_button = QPushButton("SEPARATE STEMS")
        self.separate_button.clicked.connect(self._start_separation)
        self.cancel_button = QPushButton("Cancel")
        self.cancel_button.setObjectName("danger")
        self.cancel_button.clicked.connect(self._cancel_separation)
        self.cancel_button.hide()
        buttons.addWidget(self.back_button)
        buttons.addStretch(1)
        buttons.addWidget(self.cancel_button)
        buttons.addWidget(self.separate_button)
        layout.addStretch(1)
        layout.addLayout(buttons)
        return page

    def _build_results_page(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(36, 28, 36, 28)
        self.results_title = QLabel("Stems")
        self.results_title.setStyleSheet("font-size: 26px; font-weight: 700;")
        layout.addWidget(self.results_title)

        self.mixer_container = QWidget()
        self.mixer_layout = QVBoxLayout(self.mixer_container)
        layout.addWidget(self.mixer_container, stretch=1)

        transport = QFrame()
        transport.setObjectName("panel")
        tl = QVBoxLayout(transport)
        buttons = QHBoxLayout()
        self.play_button = QPushButton("Play")
        self.play_button.clicked.connect(self._toggle_play)
        stop = QPushButton("Stop")
        stop.setObjectName("secondary")
        stop.clicked.connect(self._stop)
        self.time_label = QLabel("0:00 / 0:00")
        buttons.addWidget(self.play_button)
        buttons.addWidget(stop)
        buttons.addWidget(self.time_label)
        buttons.addStretch(1)
        tl.addLayout(buttons)
        self.seek_slider = QSlider(Qt.Horizontal)
        self.seek_slider.setRange(0, 1000)
        self.seek_slider.sliderReleased.connect(self._seek_released)
        tl.addWidget(self.seek_slider)
        master_row = QHBoxLayout()
        master_row.addWidget(QLabel("Master"))
        self.master_slider = QSlider(Qt.Horizontal)
        self.master_slider.setRange(0, 100)
        self.master_slider.setValue(100)
        self.master_slider.valueChanged.connect(self._master_changed)
        master_row.addWidget(self.master_slider)
        tl.addLayout(master_row)
        layout.addWidget(transport)

        bottom = QHBoxLayout()
        open_button = QPushButton("Open Output Folder")
        open_button.setObjectName("secondary")
        open_button.clicked.connect(self._open_output_folder)
        export_button = QPushButton("Export All Stems")
        export_button.clicked.connect(self._export_all)
        another = QPushButton("New Song")
        another.setObjectName("secondary")
        another.clicked.connect(self._new_song)
        bottom.addWidget(open_button)
        bottom.addWidget(export_button)
        bottom.addStretch(1)
        bottom.addWidget(another)
        layout.addLayout(bottom)
        return page

    def dragEnterEvent(self, event: QDragEnterEvent):
        if event.mimeData().hasUrls() and event.mimeData().urls():
            event.acceptProposedAction()

    def dropEvent(self, event: QDropEvent):
        urls = event.mimeData().urls()
        if urls:
            self._load_file(Path(urls[0].toLocalFile()))

    @Slot()
    def _select_file(self):
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Select audio file",
            str(Path.home()),
            "Audio Files (*.wav *.mp3 *.flac *.m4a *.aac)",
        )
        if path:
            self._load_file(Path(path))

    def _load_file(self, path: Path):
        try:
            info = probe_audio(path)
        except StemSplitterError as exc:
            QMessageBox.warning(self, "Cannot open audio", exc.user_message)
            return
        self.current_file = path
        self.settings.recent_file = str(path)
        self.settings_store.save(self.settings)
        self.file_label.setText(path.name)
        pieces = []
        if info.duration_seconds is not None:
            pieces.append(_format_time(info.duration_seconds))
        if info.format_name:
            pieces.append(str(info.format_name))
        if info.sample_rate:
            pieces.append(f"{info.sample_rate / 1000:.1f} kHz")
        self.info_label.setText("  ·  ".join(pieces))
        output = song_output_directory(path, Path(self.settings.output_root))
        self.output_label.setText(f"Output: {output}")
        self.status_label.setText("")
        self.progress.setValue(0)
        self.elapsed_label.setText("")
        self.stack.setCurrentWidget(self.process_page)

    def _start_separation(self):
        if not self.current_file or self.worker_thread is not None:
            return
        engine = DemucsSeparationEngine()
        if not engine.model_manager.is_installed("htdemucs"):
            answer = QMessageBox.question(
                self,
                "AI model download required",
                "The HTDemucs model weights are not installed yet. They will be downloaded once and cached locally before separation. Continue?",
            )
            if answer != QMessageBox.Yes:
                return

        output = song_output_directory(self.current_file, Path(self.settings.output_root))
        request = SeparationRequest(
            input_file=self.current_file,
            stem_mode=self.mode_combo.currentData(),
            output_directory=output,
            device=self.settings.device,
        )
        self.worker_thread = QThread(self)
        self.worker = SeparationWorker(request)
        self.worker.moveToThread(self.worker_thread)
        self.worker_thread.started.connect(self.worker.run)
        self.worker.progress.connect(self._on_progress)
        self.worker.finished.connect(self._on_finished)
        self.worker.failed.connect(self._on_failed)
        self.worker.finished.connect(self.worker_thread.quit)
        self.worker.failed.connect(self.worker_thread.quit)
        self.worker_thread.finished.connect(self._clear_worker)
        self.separate_button.setEnabled(False)
        self.back_button.setEnabled(False)
        self.cancel_button.show()
        self.processing_started = time.monotonic()
        self.elapsed_timer.start(500)
        self.worker_thread.start()

    def _cancel_separation(self):
        if self.worker:
            self.worker.cancel()
            self.status_label.setText("Cancelling…")
            self.cancel_button.setEnabled(False)

    @Slot(object)
    def _on_progress(self, update):
        self.status_label.setText(update.message)
        if update.fraction is None:
            self.progress.setRange(0, 0)
        else:
            self.progress.setRange(0, 100)
            self.progress.setValue(int(update.fraction * 100))

    @Slot(object)
    def _on_finished(self, result):
        self.elapsed_timer.stop()
        self.current_result = result
        self.status_label.setText("Complete")
        self.progress.setRange(0, 100)
        self.progress.setValue(100)
        self._show_results(result)

    @Slot(object)
    def _on_failed(self, exc):
        self.elapsed_timer.stop()
        if isinstance(exc, SeparationCancelledError):
            self.status_label.setText("Separation cancelled.")
            self.progress.setRange(0, 100)
            self.progress.setValue(0)
            return
        if isinstance(exc, StemSplitterError):
            user = exc.user_message
            detail = exc.detail
        else:
            user = "Stem separation failed."
            detail = repr(exc)
        logger.error("Separation failed: %s | %s", user, detail)
        self.status_label.setText(user)
        QMessageBox.critical(self, "Separation failed", f"{user}\n\nTechnical details:\n{detail}")

    @Slot()
    def _clear_worker(self):
        self.worker_thread.deleteLater()
        self.worker_thread = None
        self.worker = None
        self.separate_button.setEnabled(True)
        self.back_button.setEnabled(True)
        self.cancel_button.setEnabled(True)
        self.cancel_button.hide()

    def _update_elapsed(self):
        if self.processing_started:
            elapsed = time.monotonic() - self.processing_started
            self.elapsed_label.setText(f"Elapsed: {_format_time(elapsed)}")

    def _clear_layout(self, layout):
        while layout.count():
            item = layout.takeAt(0)
            widget = item.widget()
            if widget is not None:
                widget.deleteLater()
            elif item.layout() is not None:
                self._clear_layout(item.layout())

    def _show_results(self, result):
        if self.player:
            self.player.close()
        try:
            self.player = SynchronizedStemPlayer(result.stems)
        except Exception as exc:
            self.player = None
            QMessageBox.warning(self, "Playback unavailable", f"Stems were created, but playback could not start:\n{exc}")
        self._clear_layout(self.mixer_layout)
        self.results_title.setText(result.source.file_path.stem)
        for stem in result.stems:
            row = QFrame()
            row.setObjectName("panel")
            rl = QHBoxLayout(row)
            name = QLabel(stem.name)
            name.setMinimumWidth(100)
            mute = QPushButton("Mute")
            mute.setCheckable(True)
            solo = QPushButton("Solo")
            solo.setCheckable(True)
            volume = QSlider(Qt.Horizontal)
            volume.setRange(0, 150)
            volume.setValue(100)
            export = QPushButton("Export")
            export.setObjectName("secondary")
            mute.toggled.connect(lambda checked, sid=stem.id: self._set_mute(sid, checked))
            solo.toggled.connect(lambda checked, sid=stem.id: self._set_solo(sid, checked))
            volume.valueChanged.connect(lambda value, sid=stem.id: self._set_volume(sid, value))
            export.clicked.connect(lambda _=False, s=stem: self._export_one(s))
            rl.addWidget(name)
            rl.addWidget(mute)
            rl.addWidget(solo)
            rl.addWidget(volume, stretch=1)
            rl.addWidget(export)
            self.mixer_layout.addWidget(row)
        self.mixer_layout.addStretch(1)
        duration = self.player.duration_seconds if self.player else (result.source.duration_seconds or 0)
        self.time_label.setText(f"0:00 / {_format_time(duration)}")
        self.seek_slider.setValue(0)
        self.play_button.setText("Play")
        self.transport_timer.start()
        self.stack.setCurrentWidget(self.results_page)

    def _set_mute(self, stem_id, checked):
        if self.player:
            self.player.mixer.set_muted(stem_id, checked)

    def _set_solo(self, stem_id, checked):
        if self.player:
            self.player.mixer.set_solo(stem_id, checked)

    def _set_volume(self, stem_id, value):
        if self.player:
            self.player.mixer.set_volume(stem_id, value / 100.0)

    def _master_changed(self, value):
        if self.player:
            self.player.mixer.master_volume = value / 100.0

    def _toggle_play(self):
        if not self.player:
            return
        if self.player.playing:
            self.player.pause()
            self.play_button.setText("Play")
        else:
            self.player.play()
            self.play_button.setText("Pause")

    def _stop(self):
        if self.player:
            self.player.stop()
            self.play_button.setText("Play")

    def _update_transport(self):
        if not self.player:
            return
        pos = self.player.position_seconds
        dur = self.player.duration_seconds
        if not self.seek_slider.isSliderDown() and dur > 0:
            self.seek_slider.setValue(int((pos / dur) * 1000))
        self.time_label.setText(f"{_format_time(pos)} / {_format_time(dur)}")
        if not self.player.playing:
            self.play_button.setText("Play")

    def _seek_released(self):
        if self.player and self.player.duration_seconds > 0:
            self.player.seek(self.player.duration_seconds * self.seek_slider.value() / 1000.0)

    def _open_output_folder(self):
        if self.current_result:
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.current_result.output_directory)))

    def _export_all(self):
        if not self.current_result:
            return
        folder = QFileDialog.getExistingDirectory(self, "Export all stems", str(Path.home()))
        if folder:
            try:
                export_all(self.current_result, Path(folder))
                QMessageBox.information(self, "Export complete", "All stems were exported successfully.")
            except StemSplitterError as exc:
                QMessageBox.warning(self, "Export failed", exc.user_message)

    def _export_one(self, stem):
        folder = QFileDialog.getExistingDirectory(self, f"Export {stem.name}", str(Path.home()))
        if folder and self.current_result:
            try:
                export_stem(stem, Path(folder), self.current_result.source.file_path.stem)
            except StemSplitterError as exc:
                QMessageBox.warning(self, "Export failed", exc.user_message)

    def _new_song(self):
        if self.player:
            self.player.close()
            self.player = None
        self.current_result = None
        self.stack.setCurrentWidget(self.start_page)

    def closeEvent(self, event):
        if self.worker:
            self.worker.cancel()
        if self.player:
            self.player.close()
        super().closeEvent(event)
