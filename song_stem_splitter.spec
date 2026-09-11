# PyInstaller desktop build. Produces a normal folder on Windows and a .app bundle on macOS.
from __future__ import annotations

import sys

from PyInstaller.utils.hooks import collect_all

hiddenimports = []
datas = []
binaries = []
for package in ("demucs", "imageio_ffmpeg"):
    d, b, h = collect_all(package)
    datas += d
    binaries += b
    hiddenimports += h

analysis = Analysis(
    ["run_app.py"],
    pathex=["src"],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)
pyz = PYZ(analysis.pure)
exe = EXE(
    pyz,
    analysis.scripts,
    [],
    exclude_binaries=True,
    name="Song Stem Splitter",
    console=False,
)
coll = COLLECT(
    exe,
    analysis.binaries,
    analysis.datas,
    strip=False,
    upx=False,
    name="Song Stem Splitter",
)

# A real .app bundle is important on macOS: it preserves executable permissions
# inside a DMG and gives Gatekeeper a conventional application structure.
if sys.platform == "darwin":
    app = BUNDLE(
        coll,
        name="Song Stem Splitter.app",
        icon=None,
        bundle_identifier="com.jhaago.songstemsplitter",
        info_plist={
            "CFBundleName": "Song Stem Splitter",
            "CFBundleDisplayName": "Song Stem Splitter",
            "LSMinimumSystemVersion": "13.0",
            "NSHighResolutionCapable": True,
        },
    )
