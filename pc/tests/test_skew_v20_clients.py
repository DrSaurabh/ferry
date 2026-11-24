"""2.1 (M15): 2.0-shaped requests (no new headers) still work against the 2.1 PC."""
import json

from helpers import upload

OLD = {"X-Ferry-Device": "Old%20phone", "User-Agent": "okhttp/4.12.0"}


def test_old_upload_and_check_batch(srv):
    st, res = upload(srv, "old.jpg", b"abc", mtime=1700000000000, headers=OLD)
    assert st == 200 and res["result"] == "saved"
    body = json.dumps({"files": [{"name": "old.jpg", "size": 3, "mtime": 1700000000000}]}).encode()
    st, res = srv.json("POST", "/api/v1/check-batch", body=body, headers=dict(OLD, **{"Content-Length": str(len(body))}))
    assert st == 200 and res["results"][0]["duplicate"] == "old.jpg"
    st, res = srv.json("GET", "/api/v1/info", headers=OLD)
    assert st == 200 and res["protocol"] == 2 and "logs" in res["capabilities"]
    ev = [l.split("\t")[9] for l in srv.log_lines() if l.split("\t")[3] == "EVENT"]
    assert any(e.startswith("saved old.jpg") and "taken ? · GPS unknown · from Old phone" in e for e in ev)
    assert not any(e.startswith("batch ") for e in ev)


def test_bad_batch_header_ignored(srv):
    st, res = upload(srv, "b.mp4", b"x", headers=dict(OLD, **{"X-Ferry-Batch": "nonsense"}))
    assert st == 200 and res["result"] == "saved"
