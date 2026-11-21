"""HTTP API and the small web page.

By default the server listens on 127.0.0.1 only and accepts browser requests
only from itself and from browser extensions. ``--lan`` opens it to the local
network (for the phone app) and then every API call needs the access token.
"""

from __future__ import annotations

import hmac
import html
import re
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import quote

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse, Response

from . import __version__
from .attack import MODES, STRENGTHS
from .detect import AUDIO_EXT, IMAGE_EXT, CheckError, Detector
from .imageio import FORMATS
from .jobs import JobManager, QueueFull
from .protector import Protector, check_options

MAX_UPLOAD = 30 * 1024 * 1024
MAX_CHECK = 200 * 1024 * 1024
WEB = Path(__file__).parent / "web"

# Pages that may call the API from a browser: this server's own page, and extensions.
ALLOWED_ORIGIN = re.compile(
    r"^(chrome-extension://[a-p]{32}|moz-extension://[0-9a-f-]{36}"
    r"|https?://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?)$")


GALLERY = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>SENTINEL test page</title>
<style>body{font:15px/1.5 system-ui,sans-serif;margin:0;padding:24px 16px;background:#fafafa;color:#222}
main{max-width:1100px;margin:0 auto}h1{margin:0 0 4px}p{color:#555;max-width:70ch}
.g{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:14px;margin-top:18px}
figure{margin:0;background:#fff;border:1px solid #ddd;border-radius:10px;overflow:hidden}
img{width:100%;height:200px;object-fit:cover;display:block}figcaption{font-size:12px;padding:6px 10px;color:#666}
</style></head><body><main><h1>SENTINEL test page</h1>
<p>A page of real and AI-made pictures for testing the SENTINEL Chrome extension. With the extension on, each picture
gets an <b>AI</b>, <b>Unsure</b> or <b>Human</b> badge within a few seconds. The file name says what it really is.
Right-click a picture and choose <i>Protect this image with SENTINEL</i> to try the protection.</p>
<div class="g">{{CELLS}}</div></main></body></html>"""


def create_app(protector: Protector | None = None, token: str | None = None,
               detector: Detector | None = None, samples: Path | None = None) -> FastAPI:
    protector = protector or Protector()
    detector = detector or Detector()
    jobs = JobManager(protector)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        yield
        jobs.shutdown()

    app = FastAPI(title="SENTINEL backend", version=__version__, lifespan=lifespan,
                  docs_url="/api/docs", redoc_url=None, openapi_url="/api/openapi.json")
    app.state.jobs = jobs
    app.add_middleware(
        CORSMiddleware, allow_origin_regex=ALLOWED_ORIGIN.pattern,
        allow_methods=["GET", "POST", "DELETE"], allow_headers=["X-AIP-Token"],
        expose_headers=["Content-Disposition"])

    @app.middleware("http")
    async def guard(request: Request, call_next):
        if request.url.path.startswith("/api/"):
            # A web page the user happens to visit must not be able to drive this
            # server. Browsers always send Origin on cross-site POSTs.
            origin = request.headers.get("origin")
            same = origin and origin == f"{request.url.scheme}://{request.headers.get('host', '')}"
            if origin and not same and not ALLOWED_ORIGIN.match(origin):
                return JSONResponse({"detail": "This origin may not use the SENTINEL backend."}, 403)
            if token and request.method != "OPTIONS" and request.url.path != "/api/health":
                given = request.headers.get("x-aip-token") or request.query_params.get("token") or ""
                if not hmac.compare_digest(given.encode(), token.encode()):
                    return JSONResponse({"detail": "Missing or wrong access token."}, 401)
        return await call_next(request)

    def find(job_id: str):
        job = jobs.get(job_id)
        if not job:
            raise HTTPException(404, "No such job. Finished jobs are kept for one hour.")
        return job

    @app.get("/api/health")
    def health():
        return {
            "ok": True, "name": "aipoisoner", "version": __version__,
            "device": protector.registry.device, "model": protector.model,
            "model_loaded": protector.model in protector.registry.loaded(),
            "token_required": bool(token), **jobs.counts(),
            "strengths": {k: {"eps_255": round(v.eps * 255, 1), "steps": v.steps, "work_px": v.work_px}
                          for k, v in STRENGTHS.items()},
            "modes": list(MODES), "formats": sorted(FORMATS), "detectors": detector.status(),
        }

    @app.post("/api/protect", status_code=202)
    async def protect(file: UploadFile = File(...), strength: str = Form("standard"),
                      mode: str = Form("cloak"), format: str = Form("png")):
        try:
            check_options(strength, mode, format)
        except ValueError as e:
            raise HTTPException(422, str(e)) from e
        data = await file.read(MAX_UPLOAD + 1)
        if len(data) > MAX_UPLOAD:
            raise HTTPException(413, "File is larger than 30 MB.")
        if not data:
            raise HTTPException(422, "The uploaded file is empty.")
        try:
            job = jobs.submit(Path(file.filename or "image").name, data, strength, mode, format)
        except QueueFull as e:
            raise HTTPException(429, "Too many images are waiting. Try again when some have finished.") from e
        return jobs.describe(job)

    @app.get("/api/jobs/{job_id}")
    def status(job_id: str):
        return jobs.describe(find(job_id))

    @app.get("/api/jobs/{job_id}/image")
    def image(job_id: str):
        job = find(job_id)
        if job.status != "done":
            raise HTTPException(409, f"Job is {job.status}, not done.")
        ext = {"jpeg": "jpg"}.get(job.fmt, job.fmt)
        name = f"{Path(job.name).stem or 'image'}_protected.{ext}"
        return Response(job.result.image, media_type=job.result.mime, headers={
            "Content-Disposition": f"attachment; filename*=UTF-8''{quote(name)}",
            "Cache-Control": "no-store"})

    @app.get("/api/jobs/{job_id}/preview")
    def preview(job_id: str):
        job = find(job_id)
        if job.status != "done" or not job.result.preview:
            raise HTTPException(409, "No preview for this job.")
        return Response(job.result.preview, media_type="image/png", headers={"Cache-Control": "no-store"})

    @app.delete("/api/jobs/{job_id}")
    def remove(job_id: str):
        if not jobs.remove(job_id):
            raise HTTPException(404, "No such job.")
        return {"ok": True}

    @app.post("/api/check")
    async def check(file: UploadFile = File(...)):
        data = await file.read(MAX_CHECK + 1)
        if len(data) > MAX_CHECK:
            raise HTTPException(413, "File is larger than 200 MB.")
        if not data:
            raise HTTPException(422, "The uploaded file is empty.")
        try:
            return await run_in_threadpool(detector.check_file, Path(file.filename or "file").name, data)
        except CheckError as e:
            raise HTTPException(422, str(e)) from e

    def sample_files() -> list[Path]:
        if not samples or not samples.is_dir():
            return []
        ok = IMAGE_EXT | AUDIO_EXT
        return sorted(p for p in samples.rglob("*") if p.is_file() and p.suffix.lower() in ok)[:200]

    @app.get("/api/samples")
    def list_samples():
        return [{"id": i, "name": str(p.relative_to(samples)), "size": p.stat().st_size}
                for i, p in enumerate(sample_files())]

    @app.get("/api/samples/{i}/file")
    def sample_file(i: int):
        files = sample_files()
        if not 0 <= i < len(files):
            raise HTTPException(404, "No such sample.")
        return FileResponse(files[i])

    @app.post("/api/samples/{i}/check")
    async def check_sample(i: int):
        files = sample_files()
        if not 0 <= i < len(files):
            raise HTTPException(404, "No such sample.")
        try:
            return await run_in_threadpool(detector.check_file, files[i].name, files[i].read_bytes())
        except CheckError as e:
            raise HTTPException(422, str(e)) from e

    @app.get("/gallery", include_in_schema=False)
    def gallery():
        files = sample_files()
        imgs = [(i, p) for i, p in enumerate(files) if p.suffix.lower() in IMAGE_EXT]
        cells = "".join(
            f'<figure><img src="/api/samples/{i}/file" alt="{html.escape(p.name)}" loading="lazy">'
            f'<figcaption>{html.escape(p.name)}</figcaption></figure>' for i, p in imgs)
        if not imgs:
            cells = "<p>No demo images found. Start the app with --samples pointing at the test files folder.</p>"
        page = GALLERY.replace("{{CELLS}}", cells)
        return Response(page, media_type="text/html", headers={"Cache-Control": "no-cache"})

    @app.get("/", include_in_schema=False)
    def index():
        return FileResponse(WEB / "app.html", headers={"Cache-Control": "no-cache"})

    @app.get("/protect", include_in_schema=False)
    def protect_page():
        return FileResponse(WEB / "index.html", headers={"Cache-Control": "no-cache"})

    return app
