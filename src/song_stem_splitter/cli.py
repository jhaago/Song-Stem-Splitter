from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .engine.demucs_engine import DemucsSeparationEngine
from .errors import StemSplitterError
from .models import SeparationRequest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="stem-separator-engine", description="Local stem-separation engine CLI")
    sub = parser.add_subparsers(dest="command", required=True)

    separate = sub.add_parser("separate", help="Separate an audio file")
    separate.add_argument("input", type=Path)
    separate.add_argument("--mode", default="4stem", choices=["4stem"])
    separate.add_argument("--output", type=Path, default=None)
    separate.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])
    separate.add_argument("--json", action="store_true", help="Print the result as JSON")

    model = sub.add_parser("model-status", help="Check whether the default model is cached")
    model.add_argument("--model", default="htdemucs")
    return parser


def main() -> int:
    args = _parser().parse_args()
    engine = DemucsSeparationEngine()
    if args.command == "model-status":
        installed = engine.model_manager.is_installed(args.model)
        print(json.dumps({"model": args.model, "installed": installed}))
        return 0 if installed else 1

    def progress(update):
        fraction = f" {update.fraction * 100:5.1f}%" if update.fraction is not None else ""
        print(f"[{update.state.value}]{fraction} {update.message}", file=sys.stderr)

    try:
        result = engine.separate(
            SeparationRequest(
                input_file=args.input,
                stem_mode=args.mode,
                output_directory=args.output,
                device=args.device,
            ),
            progress_callback=progress,
        )
    except StemSplitterError as exc:
        print(f"Error: {exc.user_message}", file=sys.stderr)
        print(exc.detail, file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(result.to_dict(), indent=2))
    else:
        print(f"Output: {result.output_directory}")
        for stem in result.stems:
            print(f"  {stem.name}: {stem.file_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
