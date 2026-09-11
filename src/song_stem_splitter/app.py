from __future__ import annotations

import multiprocessing
import sys


def main() -> int:
    multiprocessing.freeze_support()
    from PySide6.QtWidgets import QApplication

    from .logging_config import configure_logging
    from .gui.main_window import MainWindow

    configure_logging()
    app = QApplication(sys.argv)
    app.setApplicationName("Song Stem Splitter")
    app.setOrganizationName("Song Stem Splitter")
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
