"""Bridge tests — stdlib only. A fake scoutctl on disk records its argv and
returns canned output; the server runs in-process on an ephemeral port."""

import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from scout_bridge import (  # noqa: E402
    BridgeConfig,
    detect_lan_ip,
    make_server,
    pairing_uri,
    qr_renderable,
)

FAKE_SCOUTCTL = """#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ["FAKE_LOG"], "a") as f:
    f.write(json.dumps(args) + "\\n")
if os.environ.get("FAKE_FAIL"):
    sys.stderr.write("boom: simulated failure")
    sys.exit(1)
if "list" in args:
    print(json.dumps([{"title": "t", "priority": "🔴", "status": "open",
                       "section": "🔴 Urgent", "short_prefix": "QPD1"}]))
"""


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.vault = root / "vault"
        (self.vault / "action-items").mkdir(parents=True)
        self.inbox = root / "inbox"
        self.fake_log = root / "fake-scoutctl.log"
        os.environ["FAKE_LOG"] = str(self.fake_log)
        os.environ.pop("FAKE_FAIL", None)

        self.scoutctl = root / "scoutctl"
        self.scoutctl.write_text(FAKE_SCOUTCTL)
        self.scoutctl.chmod(0o755)

        config = BridgeConfig(
            port=0,
            vault=self.vault,
            inbox=self.inbox,
            secret="testsecret",
            scoutctl=str(self.scoutctl),
        )
        self.server = make_server(config)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def request(self, path, method="GET", body=None, secret="testsecret"):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}", method=method,
            data=json.dumps(body).encode() if body is not None else None,
        )
        if secret is not None:
            req.add_header("Authorization", f"Bearer {secret}")
        if body is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, json.loads(resp.read().decode())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode() or "{}")

    def calls(self):
        if not self.fake_log.exists():
            return []
        return [json.loads(line) for line in self.fake_log.read_text().splitlines()]

    def et_date(self):
        return datetime.now(ZoneInfo("America/New_York")).strftime("%Y-%m-%d")

    def test_missing_auth_is_401(self):
        status, _ = self.request("/api/v1/health", secret=None)
        self.assertEqual(status, 401)

    def test_wrong_secret_is_401(self):
        status, _ = self.request("/api/v1/health", secret="wrong")
        self.assertEqual(status, 401)

    def test_health(self):
        status, body = self.request("/api/v1/health")
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        self.assertEqual(body["vault_date"], self.et_date())
        self.assertTrue(body["scoutctl"])

    def test_today_passes_through_scoutctl_json(self):
        status, body = self.request("/api/v1/action-items/today")
        self.assertEqual(status, 200)
        self.assertEqual(body["items"][0]["short_prefix"], "QPD1")
        self.assertEqual(body["date"], self.et_date())
        self.assertIn("generated_at", body)
        args = self.calls()[0]
        self.assertEqual(args[:2], ["action-items", "list"])
        self.assertIn("--json", args)
        self.assertIn("--include-done", args)
        self.assertIn(str(self.vault / "action-items" / f"action-items-{self.et_date()}.md"), args)

    def test_done_invokes_mark_done_by_id(self):
        status, body = self.request("/api/v1/action-items/QPD1/done", method="POST", body={})
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        args = self.calls()[0]
        self.assertEqual(args[:2], ["action-items", "mark-done"])
        self.assertIn("--by-id", args)
        self.assertIn("QPD1", args)

    def test_done_failure_maps_exit_code(self):
        os.environ["FAKE_FAIL"] = "1"
        status, body = self.request("/api/v1/action-items/QPD1/done", method="POST", body={})
        self.assertEqual(status, 200)
        self.assertFalse(body["ok"])
        self.assertIn("boom", body["error"])

    def test_snooze_passes_until(self):
        status, body = self.request(
            "/api/v1/action-items/QPD1/snooze", method="POST", body={"until": "2026-06-15"})
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        args = self.calls()[0]
        self.assertEqual(args[:2], ["action-items", "snooze"])
        self.assertIn("--until", args)
        self.assertIn("2026-06-15", args)

    def test_invalid_prefix_rejected(self):
        status, _ = self.request("/api/v1/action-items/bad..path/done", method="POST", body={})
        self.assertEqual(status, 400)

    def test_events_appended_as_jsonl(self):
        events = [
            {"v": 1, "key": f"k{i}", "package": "com.x", "post_time_ms": i,
             "title": "t", "text": "x", "big_text": None, "category": None,
             "is_ongoing": False, "removed": False,
             "captured_at": "2026-06-12T15:00:00Z"}
            for i in range(3)
        ]
        status, body = self.request(
            "/api/v1/events/notifications", method="POST", body={"events": events})
        self.assertEqual(status, 200)
        self.assertEqual(body["accepted"], 3)
        inbox_file = self.inbox / f"phone-notifications-{self.et_date()}.jsonl"
        lines = inbox_file.read_text().splitlines()
        self.assertEqual(len(lines), 3)
        self.assertEqual(json.loads(lines[0])["key"], "k0")

    def test_malformed_json_is_400(self):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/api/v1/events/notifications",
            method="POST", data=b"{not json",
        )
        req.add_header("Authorization", "Bearer testsecret")
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                status = resp.status
        except urllib.error.HTTPError as e:
            status = e.code
        self.assertEqual(status, 400)

    def test_unknown_path_is_404(self):
        status, _ = self.request("/api/v1/nope")
        self.assertEqual(status, 404)

    def test_status_requires_auth(self):
        status, _ = self.request("/api/v1/status", secret=None)
        self.assertEqual(status, 401)

    def test_status_returns_all_keys(self):
        logs = self.vault / ".scout-logs"
        logs.mkdir(parents=True, exist_ok=True)
        (logs / f"scout-{self.et_date()}_09-00.log").write_text("a run\n")
        status, body = self.request("/api/v1/status")
        self.assertEqual(status, 200)
        for key in ("running", "next_run", "today_spend_usd", "connectors", "recent_runs"):
            self.assertIn(key, body)
        self.assertTrue(any(r["id"].startswith("scout-") for r in body["recent_runs"]))

    def test_run_now_unknown_type_is_400(self):
        status, body = self.request("/api/v1/runs", method="POST", body={"type": "garbage"})
        self.assertEqual(status, 400)

    def test_run_now_starts_with_fake_runner(self):
        # a fake run-scout.sh that just exits 0, so no tokens are spent
        (self.vault / "run-scout.sh").write_text("#!/bin/bash\nexit 0\n")
        (self.vault / "run-scout.sh").chmod(0o755)
        status, body = self.request("/api/v1/runs", method="POST",
                                    body={"type": "consolidation", "bypass_budget": True})
        self.assertEqual(status, 200)
        self.assertTrue(body["started"])

    def test_retry_bad_run_id_is_400(self):
        status, _ = self.request("/api/v1/runs/retry", method="POST",
                                 body={"run_id": "bad id"})
        self.assertEqual(status, 400)

    def test_run_log_tail_and_404(self):
        logs = self.vault / ".scout-logs"
        logs.mkdir(parents=True, exist_ok=True)
        rid = f"scout-{self.et_date()}_09-00"
        (logs / f"{rid}.log").write_text("l1\nl2\nl3\nl4\nl5\n")
        status, body = self.request(f"/api/v1/runs/{rid}/log?tail=2")
        self.assertEqual(status, 200)
        self.assertEqual(body["lines"], ["l4", "l5"])
        status2, _ = self.request("/api/v1/runs/scout-2000-01-01_00-00/log")
        self.assertEqual(status2, 404)
        status3, _ = self.request("/api/v1/runs/bad..id/log")
        self.assertEqual(status3, 400)


class PairingTest(unittest.TestCase):
    def test_pairing_uri_encodes_url_and_secret(self):
        uri = pairing_uri("http://10.0.0.199:8787", "abc123")
        self.assertEqual(uri, "scout://pair?u=http%3A%2F%2F10.0.0.199%3A8787&s=abc123")

    def test_pairing_uri_encodes_reserved_chars_in_secret(self):
        uri = pairing_uri("http://h:1", "a&b=c")
        self.assertIn("s=a%26b%3Dc", uri)

    def test_detect_lan_ip_returns_dotted_quad(self):
        ip = detect_lan_ip()
        self.assertRegex(ip, r"^\d+\.\d+\.\d+\.\d+$")

    def test_qr_renderable_reflects_qrencode_presence(self):
        self.assertFalse(qr_renderable(qrencode="definitely-not-a-binary-xyz"))


if __name__ == "__main__":
    unittest.main()
