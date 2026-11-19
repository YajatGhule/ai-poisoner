import io
import time

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from aipoisoner import jobs as jobs_mod
from aipoisoner.server import create_app
from conftest import picture


@pytest.fixture
def client(protector):
    with TestClient(create_app(protector)) as c:
        yield c


def wait(client, job_id, want=("done", "error", "cancelled"), headers=None):
    for _ in range(600):
        j = client.get(f"/api/jobs/{job_id}", headers=headers or {}).json()
        if j["status"] in want:
            return j
        time.sleep(0.05)
    raise AssertionError("job did not finish")


def post(client, data=None, **form):
    return client.post("/api/protect", files={"file": ("cat photo.png", data or picture(), "image/png")},
                       data={"strength": "light", **form})


def test_health(client):
    h = client.get("/api/health").json()
    assert h["ok"] and h["device"] == "cpu" and set(h["strengths"]) == {"light", "standard", "strong"}


def test_full_flow(client):
    r = post(client)
    assert r.status_code == 202
    j = wait(client, r.json()["id"])
    assert j["status"] == "done" and j["progress"] == 1.0 and j["metrics"]["latent_shift"] > 0
    img = client.get(f"/api/jobs/{j['id']}/image")
    assert img.headers["content-type"] == "image/png"
    assert "cat%20photo_protected.png" in img.headers["content-disposition"]
    assert Image.open(io.BytesIO(img.content)).size == (160, 120)
    assert client.get(f"/api/jobs/{j['id']}/preview").status_code == 200
    assert client.delete(f"/api/jobs/{j['id']}").json() == {"ok": True}
    assert client.get(f"/api/jobs/{j['id']}").status_code == 404


def test_bad_requests(client):
    assert post(client, strength="extreme").status_code == 422
    assert post(client, format="gif").status_code == 422
    assert client.post("/api/protect", files={"file": ("a.png", b"", "image/png")}).status_code == 422
    assert client.get("/api/jobs/nope").status_code == 404
    assert client.delete("/api/jobs/nope").status_code == 404
    j = wait(client, post(client, b"this is not an image").json()["id"])
    assert j["status"] == "error" and "not an image" in j["error"]
    assert client.get(f"/api/jobs/{j['id']}/image").status_code == 409


def test_other_websites_are_refused(client):
    assert client.get("/api/health", headers={"Origin": "https://evil.example"}).status_code == 403
    assert post_with_origin(client, "https://evil.example").status_code == 403
    ext = "chrome-extension://" + "a" * 32
    r = client.get("/api/health", headers={"Origin": ext})
    assert r.status_code == 200 and r.headers["access-control-allow-origin"] == ext
    assert client.get("/api/health", headers={"Origin": "http://localhost:8765"}).status_code == 200


def post_with_origin(client, origin):
    return client.post("/api/protect", files={"file": ("a.png", picture(), "image/png")}, headers={"Origin": origin})


def test_token_is_required_when_set(protector):
    with TestClient(create_app(protector, token="s3cret")) as c:
        assert c.get("/api/health").json()["token_required"] is True
        assert post(c).status_code == 401
        r = c.post("/api/protect", files={"file": ("a.png", picture(), "image/png")},
                   data={"strength": "light"}, headers={"X-AIP-Token": "s3cret"})
        assert r.status_code == 202
        assert c.get(f"/api/jobs/{r.json()['id']}").status_code == 401
        assert wait(c, r.json()["id"], headers={"X-AIP-Token": "s3cret"})["status"] == "done"


def test_queue_limit_and_cancel(client, monkeypatch):
    monkeypatch.setattr(jobs_mod, "MAX_WAITING", 2)
    big = picture(640, 480)
    ids = [post(client, big, strength="strong").json()["id"] for _ in range(3)]
    codes = [post(client, big).status_code for _ in range(3)]
    assert 429 in codes
    for i in ids:
        client.delete(f"/api/jobs/{i}")
    for i in ids:
        j = client.get(f"/api/jobs/{i}")
        assert j.status_code == 404 or wait(client, i)["status"] in ("cancelled", "done")


def test_web_page_is_served(client):
    r = client.get("/")
    assert r.status_code == 200 and "Protect an" in r.text
