"""HTTP API and the small web page.

By default the server listens on 127.0.0.1 only and accepts browser requests
only from itself and from browser extensions. ``--lan`` opens it to the local
network (for the phone app) and then every API call needs the access token.
"""

from __future__ import annotations

import hmac
import re
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import quote

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response

from . import __version__
from .attack import MODES, STRENGTHS
from .imageio import FORMATS
from .jobs import JobManager, QueueFull
from .protector import Protector, check_options

MAX_UPLOAD = 30 * 1024 * 1024
WEB = Path(__file__).parent / "web"

# Pages that may call the API from a browser: this server's own page, and extensions.
ALLOWED_ORIGIN = re.compile(
    r"^(chrome-extension://[a-p]{32}|moz-extension://[0-9a-f-]{36}"
    r"|https?://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?)$")


def create_app(protector: Protector | None = None, token: str | None = None) -> FastAPI:
    protector = protector or Protector()
    jobs = JobManager(protector)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        yield
        jobs.shutdown()

    app = FastAPI(title="AI Poisoner backend", version=__version__, lifespan=lifespan,
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
                return JSONResponse({"detail": "This origin may not use the AI Poisoner backend."}, 403)
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
            "modes": list(MODES), "formats": sorted(FORMATS),
        }

    @app.post("/api/protect", status_code=202)
    async def protect(file: UploadFile = File(...), strength: str = Form("standard"),
                      mode: str = Form("cloak"), format: str = Form("png")):
        try:
            check_options(strength, mode, format)
        except ValueError as e:
            raise HTTPException(422, str(e))
        data = await file.read(MAX_UPLOAD + 1)
        if len(data) > MAX_UPLOAD:
            raise HTTPException(413, "File is larger than 30 MB.")
        if not data:
            raise HTTPException(422, "The uploaded file is empty.")
        try:
            job = jobs.submit(Path(file.filename or "image").name, data, strength, mode, format)
        except QueueFull:
            raise HTTPException(429, "Too many images are waiting. Try again when some have finished.")
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

    @app.get("/", include_in_schema=False)
    def index():
        return FileResponse(WEB / "index.html", headers={"Cache-Control": "no-cache"})

    return app
