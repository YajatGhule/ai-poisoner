"""A small in-memory job queue.

Protecting one image takes minutes on a CPU, so the HTTP request only queues
the work and the client polls. One worker thread runs jobs in order: two
optimisations at once would only make both slower.
"""

from __future__ import annotations

import queue
import threading
import time
import uuid
from dataclasses import dataclass, field

from .attack import Cancelled
from .imageio import BadImage
from .protector import Protector, Result

MAX_WAITING = 8          # jobs allowed in the queue
KEEP_SECONDS = 60 * 60   # finished jobs are forgotten after an hour


class QueueFull(Exception):
    pass


@dataclass
class Job:
    id: str
    name: str
    data: bytes | None
    strength: str
    mode: str
    fmt: str
    status: str = "queued"       # queued, running, done, error, cancelled
    step: int = 0
    steps: int = 0
    error: str | None = None
    result: Result | None = None
    created: float = field(default_factory=time.time)
    started: float | None = None
    finished: float | None = None
    cancel: threading.Event = field(default_factory=threading.Event)

    def public(self, position: int = 0) -> dict:
        eta = None
        if self.status == "running" and self.step and self.started:
            eta = round((time.time() - self.started) / self.step * (self.steps - self.step))
        out = {
            "id": self.id, "name": self.name, "status": self.status,
            "strength": self.strength, "mode": self.mode, "format": self.fmt,
            "step": self.step, "steps": self.steps,
            "progress": round(self.step / self.steps, 3) if self.steps else 0.0,
            "eta_seconds": eta, "queue_position": position, "error": self.error,
        }
        if self.result:
            r = self.result
            out.update(metrics=r.metrics, width=r.width, height=r.height,
                       seconds=r.seconds, bytes=len(r.image), mime=r.mime)
        return out


class JobManager:
    def __init__(self, protector: Protector):
        self.protector = protector
        self._jobs: dict[str, Job] = {}
        self._queue: queue.Queue[str | None] = queue.Queue()
        self._lock = threading.Lock()
        self._thread = threading.Thread(target=self._work, name="aipoisoner-worker", daemon=True)
        self._thread.start()

    # ---- called from request handlers -------------------------------------------------

    def submit(self, name: str, data: bytes, strength: str, mode: str, fmt: str) -> Job:
        with self._lock:
            self._forget_old()
            waiting = sum(j.status == "queued" for j in self._jobs.values())
            if waiting >= MAX_WAITING:
                raise QueueFull()
            job = Job(uuid.uuid4().hex, name, data, strength, mode, fmt)
            self._jobs[job.id] = job
        self._queue.put(job.id)
        return job

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            return self._jobs.get(job_id)

    def describe(self, job: Job) -> dict:
        with self._lock:
            ahead = sum(1 for j in self._jobs.values()
                        if j.status in ("queued", "running") and j.created < job.created)
        return job.public(ahead if job.status == "queued" else 0)

    def remove(self, job_id: str) -> bool:
        """Cancel a waiting or running job, or drop a finished one."""
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return False
            job.cancel.set()
            if job.status == "queued":
                job.status, job.data, job.finished = "cancelled", None, time.time()
            elif job.status != "running":
                del self._jobs[job_id]
        return True

    def counts(self) -> dict:
        with self._lock:
            return {s: sum(j.status == s for j in self._jobs.values()) for s in ("queued", "running")}

    def shutdown(self) -> None:
        with self._lock:
            for j in self._jobs.values():
                j.cancel.set()
        self._queue.put(None)
        self._thread.join(timeout=5)

    # ---- worker thread ----------------------------------------------------------------

    def _forget_old(self) -> None:
        cutoff = time.time() - KEEP_SECONDS
        for jid in [k for k, j in self._jobs.items() if j.finished and j.finished < cutoff]:
            del self._jobs[jid]

    def _work(self) -> None:
        while True:
            job_id = self._queue.get()
            if job_id is None:
                return
            job = self.get(job_id)
            if not job or job.status != "queued":
                continue
            self._run(job)

    def _run(self, job: Job) -> None:
        job.status, job.started = "running", time.time()

        def on_step(i: int, n: int, _loss: float) -> None:
            job.step, job.steps = i, n

        try:
            job.result = self.protector.protect(
                job.data, job.strength, job.mode, job.fmt,
                on_step=on_step, should_stop=job.cancel.is_set)
            job.status = "done"
        except Cancelled:
            job.status = "cancelled"
        except BadImage as e:
            job.status, job.error = "error", str(e)
        except Exception as e:  # a model download or out-of-memory failure must not kill the worker
            job.status, job.error = "error", f"{type(e).__name__}: {e}"
        finally:
            job.data = None          # the upload is not needed after this
            job.finished = time.time()
