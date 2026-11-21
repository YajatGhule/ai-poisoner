"""Command line: aipoisoner serve | protect | bench."""

from __future__ import annotations

import argparse
import contextlib
import os
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
    print(f"SENTINEL backend {__version__}: http://{'127.0.0.1' if host == '0.0.0.0' else host}:{args.port}")
    uvicorn.run(create_app(_protector(args), token), host=host, port=args.port, log_level="warning")
    return 0


def cmd_app(args) -> int:
    """The SENTINEL desktop app: the local server plus its page in an app window."""
    import os
    import subprocess
    import threading
    import time
    import urllib.request
    import webbrowser

    import uvicorn

    from .detect import Detector
    from .server import create_app

    url = f"http://127.0.0.1:{args.port}/"
    try:   # already running (e.g. launched twice): just open another window
        urllib.request.urlopen(url + "api/health", timeout=1)
        running = True
    except OSError:
        running = False
    server = None
    if not running:
        samples = Path(args.samples).expanduser() if args.samples else None
        detector = Detector()
        app = create_app(_protector(args), detector=detector, samples=samples)
        server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=args.port, log_level="warning"))
        threading.Thread(target=server.run, daemon=True).start()

        def warm():   # load the detectors now, so the first check in a demo is quick
            for kind in ("image", "voice", "music"):
                try:
                    detector._get(kind)
                except Exception as e:   # noqa: BLE001 - shown again, with context, when the check runs
                    print(f"{kind} model not ready yet: {e}", file=sys.stderr)
        threading.Thread(target=warm, daemon=True).start()
        for _ in range(100):
            if server.started:
                break
            time.sleep(0.1)
    print(f"SENTINEL {__version__}: {url}")
    if args.no_window:
        if server:
            threading.Event().wait()
        return 0
    browser = find_browser()
    if browser:
        # A separate profile makes the window its own process, so closing it also stops the server.
        profile = Path(os.environ.get("XDG_CACHE_HOME") or os.environ.get("LOCALAPPDATA") or Path.home() / ".cache") \
            / "sentinel" / "window"
        subprocess.run([browser, f"--app={url}", f"--user-data-dir={profile}", "--no-first-run",
                        "--no-default-browser-check", "--disable-background-mode", "--window-size=1100,900"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        webbrowser.open(url)
        if server:
            print("Press Ctrl+C to stop.")
            with contextlib.suppress(KeyboardInterrupt):
                threading.Event().wait()
    if server:
        server.should_exit = True
    return 0


def find_browser() -> str | None:
    """A Chromium-based browser for the app window: Chrome, Edge (always on Windows 10/11), Chromium or Brave."""
    import shutil
    names = ("google-chrome-stable", "google-chrome", "chrome", "chromium", "chromium-browser",
             "microsoft-edge-stable", "microsoft-edge", "msedge", "brave-browser", "brave")
    for n in names:
        if shutil.which(n):
            return shutil.which(n)
    if sys.platform == "win32":
        roots = [os.environ.get(k) for k in ("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA")]
        rel = [r"Google\Chrome\Application\chrome.exe", r"Microsoft\Edge\Application\msedge.exe",
               r"BraveSoftware\Brave-Browser\Application\brave.exe"]
        for r in rel:
            for root in roots:
                if root and Path(root, r).is_file():
                    return str(Path(root, r))
    if sys.platform == "darwin":
        for app in ("Google Chrome", "Microsoft Edge", "Brave Browser", "Chromium"):
            p = Path(f"/Applications/{app}.app/Contents/MacOS/{app}")
            if p.is_file():
                return str(p)
    return None


def cmd_setup(args) -> int:
    """Download every model now, so the app works offline afterwards."""
    from .detect import Detector
    d = Detector()
    for kind in ("image", "voice", "music"):
        print(f"{kind} detector: {d.download(kind)}", flush=True)
    from .models import load
    load("taesd", "cpu")
    print("protection encoder (TAESD): ready", flush=True)
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

    a = sub.add_parser("app", help="open the SENTINEL desktop app (check files for AI, protect images)")
    a.add_argument("--port", type=int, default=8765)
    a.add_argument("--samples", default=os.environ.get("SENTINEL_SAMPLES"),
                   help="folder of demo files to list in the app (or set SENTINEL_SAMPLES)")
    a.add_argument("--no-window", action="store_true", help="only run the server")
    _add_common(a)
    a.set_defaults(func=cmd_app)

    u = sub.add_parser("setup", help="download all models now (for offline use)")
    u.set_defaults(func=cmd_setup)

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


def app_main() -> int:
    """The sentinel command: same as aipoisoner app."""
    return main(["app", *sys.argv[1:]])


if __name__ == "__main__":
    sys.exit(main())
