"""Command line: aipoisoner serve | protect | bench."""

from __future__ import annotations

import argparse
import json
import secrets
import sys
from pathlib import Path

from . import __version__
from .attack import MODES, STRENGTHS
from .imageio import FORMATS, BadImage

IMAGE_TYPES = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff"}


def _add_common(p: argparse.ArgumentParser) -> None:
    p.add_argument("--model", default="taesd", choices=["taesd", "sd-vae"],
                   help="encoder to aim at (default: taesd, fast on a CPU)")
    p.add_argument("--device", default="auto", help="auto, cpu or cuda")


def _protector(args):
    from .models import Registry
    from .protector import Protector
    return Protector(Registry(args.device), args.model)


def cmd_serve(args) -> int:
    import uvicorn
    from .server import create_app
    token = None
    host = args.host
    if args.lan:
        host = "0.0.0.0"
        token = args.token or secrets.token_urlsafe(9)
        print(f"Open to your local network. Access token: {token}\n"
              f"In the phone app, enter this computer's address (port {args.port}) and that token.")
    elif args.token:
        token = args.token
    print(f"AI Poisoner backend {__version__}: http://{'127.0.0.1' if host == '0.0.0.0' else host}:{args.port}")
    uvicorn.run(create_app(_protector(args), token), host=host, port=args.port, log_level="warning")
    return 0


def cmd_protect(args) -> int:
    protector = _protector(args)
    out_dir = Path(args.out) if args.out else None
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)
    failed = 0
    for src in map(Path, args.images):
        def show(i, n, _loss, name=src.name):
            print(f"\r{name}: step {i}/{n}", end="", file=sys.stderr, flush=True)
        try:
            r = protector.protect(src.read_bytes(), args.strength, args.mode, args.format, on_step=show)
        except (BadImage, OSError) as e:
            print(f"\n{src}: {e}", file=sys.stderr)
            failed += 1
            continue
        ext = {"jpeg": "jpg"}.get(args.format, args.format)
        dst = (out_dir or src.parent) / f"{src.stem}_protected.{ext}"
        dst.write_bytes(r.image)
        m = r.metrics
        print(f"\r{src.name} -> {dst}  ({r.seconds}s, PSNR {m['psnr_db']} dB, "
              f"latent shift {m['latent_shift']}, {m['times_random_noise']}x random noise)")
    return 1 if failed else 0


def cmd_bench(args) -> int:
    from .bench import run
    files = sorted(p for p in Path(args.folder).rglob("*") if p.suffix.lower() in IMAGE_TYPES)[: args.limit]
    if not files:
        print("No images found.", file=sys.stderr)
        return 1
    summary = run(files, _protector(args), args.strength, args.mode, check_with=args.check_with,
                  device=args.device, save_dir=Path(args.save) if args.save else None)
    text = json.dumps(summary, indent=2)
    if args.json:
        Path(args.json).write_text(text + "\n")
    print(text)
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="aipoisoner", description="Protect images against AI training and editing.")
    ap.add_argument("--version", action="version", version=__version__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("serve", help="run the local server used by the extension and the phone app")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=8765)
    s.add_argument("--lan", action="store_true", help="accept connections from your local network (needs a token)")
    s.add_argument("--token", help="access token (made up for you with --lan if not given)")
    _add_common(s)
    s.set_defaults(func=cmd_serve)

    p = sub.add_parser("protect", help="protect image files")
    p.add_argument("images", nargs="+")
    p.add_argument("-o", "--out", help="output folder (default: next to each image)")
    p.add_argument("--strength", default="standard", choices=sorted(STRENGTHS))
    p.add_argument("--mode", default="cloak", choices=MODES)
    p.add_argument("--format", default="png", choices=sorted(FORMATS))
    _add_common(p)
    p.set_defaults(func=cmd_protect)

    b = sub.add_parser("bench", help="measure the protection on a folder of images")
    b.add_argument("folder")
    b.add_argument("--limit", type=int, default=12)
    b.add_argument("--strength", default="standard", choices=sorted(STRENGTHS))
    b.add_argument("--mode", default="cloak", choices=MODES)
    b.add_argument("--check-with", default="sd-vae", choices=["taesd", "sd-vae", "none"],
                   help="second encoder to measure the result with (default: the real SD encoder)")
    b.add_argument("--save", help="folder to keep the protected images in")
    b.add_argument("--json", help="write the summary to this file")
    _add_common(b)
    b.set_defaults(func=cmd_bench)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
