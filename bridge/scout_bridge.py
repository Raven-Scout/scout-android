#!/usr/bin/env python3
"""Scout bridge — the Mac-side HTTP server the Android app pairs with.

Zero dependencies (stdlib only). Wraps the existing `scoutctl action-items`
CLI for reads/writes and appends phone-captured notification events to the
vault's `.scout-inbox/` as JSONL. See docs/superpowers/specs/
2026-06-12-scout-android-v1-design.md §4 for the API contract.

Security model: bearer shared-secret over LAN HTTP (single-user-trust v1).
The secret is auto-generated to ~/.scout-secrets/scout-bridge.secret (0600)
on first start and printed so it can be typed into the app's Settings.
"""

import argparse
import hmac
import json
import re
import secrets
import shutil
import socket
import subprocess
import sys
import urllib.parse
from dataclasses import dataclass
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo

from run_launcher import RUN_ID_RE, RunLauncher
from status_reader import StatusReader

ET = ZoneInfo("America/New_York")
PREFIX_RE = re.compile(r"^[A-Z0-9]{2,8}$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
SCOUTCTL_TIMEOUT_S = 60


@dataclass
class BridgeConfig:
    port: int
    vault: Path
    inbox: Path
    secret: str
    scoutctl: str


def et_date() -> str:
    """Vault filenames follow the ET convention (CLAUDE.md hard rule);
    timestamps inside payloads stay UTC ISO-8601."""
    return datetime.now(ET).strftime("%Y-%m-%d")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def detect_lan_ip() -> str:
    """Best-effort LAN IP: a connected UDP socket reveals the outbound
    interface address without sending any packets. Falls back to loopback."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def pairing_uri(base_url: str, secret: str) -> str:
    """scout://pair?u=<url>&s=<secret> — scanned by the app's in-app scanner,
    and registered as a deep link so a stock camera scan can pair too."""
    return "scout://pair?" + urllib.parse.urlencode({"u": base_url, "s": secret})


def qr_renderable(qrencode: str = "qrencode") -> bool:
    return shutil.which(qrencode) is not None


def print_pairing_qr(uri: str, qrencode: str = "qrencode") -> None:
    if qr_renderable(qrencode):
        result = subprocess.run(
            [qrencode, "-t", "ANSIUTF8", "-m", "2", uri],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            print(result.stdout)
            print("Scan from Scout → Settings → 'Scan QR to pair'"
                  " (or any camera app — the link opens Scout).")
            return
    print("(install `brew install qrencode` for a scannable QR)")
    print(f"Pairing link: {uri}")


def load_or_create_secret(path: Path) -> str:
    if path.exists():
        return path.read_text().strip()
    path.parent.mkdir(parents=True, exist_ok=True)
    secret = secrets.token_hex(32)
    path.write_text(secret + "\n")
    path.chmod(0o600)
    print(f"Generated new bridge secret at {path}")
    return secret


def make_server(config: BridgeConfig) -> ThreadingHTTPServer:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        # -- plumbing ------------------------------------------------------
        def log_message(self, format, *args):  # noqa: A002 — stdlib signature
            print(f"[bridge] {self.address_string()} {format % args}", file=sys.stderr)

        def reply(self, status: int, payload: dict):
            body = json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def authorized(self) -> bool:
            header = self.headers.get("Authorization", "")
            token = header[7:] if header.startswith("Bearer ") else ""
            return hmac.compare_digest(token, config.secret)

        def read_json(self):
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            if not raw:
                return {}
            return json.loads(raw)

        def today_path(self) -> Path:
            return config.vault / "action-items" / f"action-items-{et_date()}.md"

        def run_scoutctl(self, *args: str) -> subprocess.CompletedProcess:
            return subprocess.run(
                [config.scoutctl, *args],
                capture_output=True, text=True, timeout=SCOUTCTL_TIMEOUT_S,
            )

        # -- routes --------------------------------------------------------
        def do_GET(self):
            if not self.authorized():
                return self.reply(401, {"ok": False, "error": "unauthorized"})
            if self.path == "/api/v1/health":
                return self.reply(200, {
                    "ok": True,
                    "vault_date": et_date(),
                    "scoutctl": shutil.which(config.scoutctl) is not None
                    or Path(config.scoutctl).exists(),
                })
            if self.path == "/api/v1/action-items/today":
                return self.handle_today()
            if self.path == "/api/v1/status":
                return self.reply(200, StatusReader(config.vault).status())
            log_match = re.match(r"^/api/v1/runs/([^/]+)/log(?:\?tail=(\d+))?$", self.path)
            if log_match:
                return self.handle_run_log(log_match.group(1), log_match.group(2))
            return self.reply(404, {"ok": False, "error": "not found"})

        def do_POST(self):
            if not self.authorized():
                return self.reply(401, {"ok": False, "error": "unauthorized"})
            try:
                body = self.read_json()
            except (json.JSONDecodeError, ValueError):
                return self.reply(400, {"ok": False, "error": "malformed JSON"})

            action_match = re.match(r"^/api/v1/action-items/([^/]+)/(done|snooze)$", self.path)
            if action_match:
                prefix, action = action_match.groups()
                if not PREFIX_RE.match(prefix):
                    return self.reply(400, {"ok": False, "error": "invalid prefix"})
                if action == "done":
                    return self.handle_mark_done(prefix)
                return self.handle_snooze(prefix, body)
            if self.path == "/api/v1/events/notifications":
                return self.handle_events(body)
            if self.path == "/api/v1/runs":
                return self.handle_run_now(body)
            if self.path == "/api/v1/runs/retry":
                return self.handle_run_retry(body)
            return self.reply(404, {"ok": False, "error": "not found"})

        # -- handlers ------------------------------------------------------
        def handle_today(self):
            result = self.run_scoutctl(
                "action-items", "list", "--json", "--include-done", str(self.today_path()),
            )
            if result.returncode != 0:
                return self.reply(200, {"ok": False, "error": result.stderr.strip()[-500:]})
            try:
                items = json.loads(result.stdout)
            except json.JSONDecodeError:
                return self.reply(200, {"ok": False, "error": "scoutctl returned non-JSON"})
            return self.reply(200, {
                "date": et_date(), "generated_at": utc_now(), "items": items,
            })

        def handle_mark_done(self, prefix: str):
            result = self.run_scoutctl(
                "action-items", "mark-done", str(self.today_path()), "--by-id", prefix,
            )
            return self.reply_scoutctl(result)

        def handle_snooze(self, prefix: str, body: dict):
            until = str(body.get("until", ""))
            if not DATE_RE.match(until):
                return self.reply(400, {"ok": False, "error": "until must be YYYY-MM-DD"})
            result = self.run_scoutctl(
                "action-items", "snooze", str(self.today_path()),
                "--until", until, "--by-id", prefix,
            )
            return self.reply_scoutctl(result)

        def reply_scoutctl(self, result: subprocess.CompletedProcess):
            if result.returncode != 0:
                return self.reply(200, {"ok": False, "error": result.stderr.strip()[-500:]})
            return self.reply(200, {"ok": True})

        def handle_events(self, body: dict):
            events = body.get("events")
            if not isinstance(events, list):
                return self.reply(400, {"ok": False, "error": "events must be a list"})
            config.inbox.mkdir(parents=True, exist_ok=True)
            inbox_file = config.inbox / f"phone-notifications-{et_date()}.jsonl"
            with inbox_file.open("a", encoding="utf-8") as f:
                for event in events:
                    f.write(json.dumps(event, ensure_ascii=False) + "\n")
            return self.reply(200, {"ok": True, "accepted": len(events)})

        def handle_run_now(self, body: dict):
            run_type = str(body.get("type", ""))
            result = RunLauncher(config.vault).run(run_type, bool(body.get("bypass_budget")))
            if not result["started"] and result.get("reason") == "unknown_type":
                return self.reply(400, {"started": False, "reason": "unknown_type"})
            return self.reply(200, result)

        def handle_run_retry(self, body: dict):
            run_id = str(body.get("run_id", ""))
            result = RunLauncher(config.vault).retry(run_id, bool(body.get("bypass_budget")))
            if not result["started"] and result.get("reason") == "bad_run_id":
                return self.reply(400, {"started": False, "reason": "bad_run_id"})
            if not result["started"] and result.get("reason") == "not_found":
                return self.reply(404, {"started": False, "reason": "not_found"})
            return self.reply(200, result)

        def handle_run_log(self, run_id: str, tail):
            if not RUN_ID_RE.match(run_id):
                return self.reply(400, {"ok": False, "error": "bad run_id"})
            log = config.vault / ".scout-logs" / f"{run_id}.log"
            if not log.exists():
                return self.reply(404, {"ok": False, "error": "no such run"})
            n = min(int(tail), 1000) if tail else 200
            lines = log.read_text(errors="replace").splitlines()
            return self.reply(200, {"id": run_id, "lines": lines[-n:]})

    return ThreadingHTTPServer(("0.0.0.0", config.port), Handler)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--vault", type=Path, default=Path.home() / "Scout")
    parser.add_argument("--inbox", type=Path, default=None,
                        help="default: <vault>/.scout-inbox")
    parser.add_argument("--secret-file", type=Path,
                        default=Path.home() / ".scout-secrets" / "scout-bridge.secret")
    parser.add_argument("--scoutctl", default="scoutctl")
    parser.add_argument("--advertise-url", default=None,
                        help="URL to encode in the pairing QR "
                             "(default: http://<detected-lan-ip>:<port>)")
    args = parser.parse_args()

    config = BridgeConfig(
        port=args.port,
        vault=args.vault.expanduser(),
        inbox=(args.inbox or args.vault.expanduser() / ".scout-inbox"),
        secret=load_or_create_secret(args.secret_file.expanduser()),
        scoutctl=args.scoutctl,
    )
    server = make_server(config)
    print(f"Scout bridge listening on :{config.port}")
    print(f"  vault: {config.vault}")
    print(f"  inbox: {config.inbox}")
    advertise = args.advertise_url or f"http://{detect_lan_ip()}:{config.port}"
    print(f"  pairing URL: {advertise}")
    print()
    print_pairing_qr(pairing_uri(advertise, config.secret))
    print(f"(manual pairing secret: {config.secret})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")
    return 0


if __name__ == "__main__":
    sys.exit(main())
