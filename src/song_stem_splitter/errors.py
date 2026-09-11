from __future__ import annotations


class StemSplitterError(Exception):
    """Base exception with a user-facing message and optional diagnostic detail."""

    def __init__(self, user_message: str, detail: str | None = None):
        super().__init__(detail or user_message)
        self.user_message = user_message
        self.detail = detail or user_message


class UnsupportedInputError(StemSplitterError):
    pass


class AudioDecodeError(StemSplitterError):
    pass


class InsufficientDiskSpaceError(StemSplitterError):
    pass


class ModelUnavailableError(StemSplitterError):
    pass


class ModelDownloadError(StemSplitterError):
    pass


class SeparationFailedError(StemSplitterError):
    pass


class SeparationCancelledError(StemSplitterError):
    pass


class ExportError(StemSplitterError):
    pass
