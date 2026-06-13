"""StatusReader — derives Scout's operational status from vault files, the same
sources the macOS Scout.app reads (.scout-logs/, .scout-state/schedule.yaml,
knowledge-base/connector-health.md). Pure parsing, no HTTP. See
docs/superpowers/specs/2026-06-13-scout-android-control-center-design.md."""

import json
import os
import re
from datetime import datetime, timedelta
from pathlib import Path

# A run log is <type>-YYYY-MM-DD_HH-MM.log; the basename (sans .log) is the run id.
RUN_ID_RE = re.compile(r"^([a-z]+)-(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})$")
WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
_OUTCOME_WINDOW = timedelta(hours=4)
_ALERT_WINDOW = timedelta(hours=24)


class StatusReader:
    def __init__(self, vault, now=None):
        self.vault = Path(vault)
        self.logs = self.vault / ".scout-logs"
        self.now = now or datetime.now()

    # -- runs ----------------------------------------------------------------
    def _runs_raw(self):
        """Every run log as (id, type, started_datetime), newest first."""
        out = []
        if not self.logs.is_dir():
            return out
        for path in self.logs.glob("*.log"):
            m = RUN_ID_RE.match(path.stem)
            if not m:
                continue  # excludes launchd-*.log and any non-run log
            kind, y, mo, d, hh, mm = m.groups()
            started = datetime(int(y), int(mo), int(d), int(hh), int(mm))
            out.append((path.stem, kind, started))
        out.sort(key=lambda r: r[2], reverse=True)
        return out

    def recent_runs(self, limit=10):
        raw = self._runs_raw()
        running_id = raw[0][0] if (raw and self.running().get("active")) else None
        usage = self._usage_entries()
        spend_by_date = self._spend_by_local_minute()
        runs = []
        for run_id, kind, started in self._runs_raw()[:limit]:
            matched = self._match_usage(started, usage)
            if run_id == running_id:
                outcome = "running"
            elif matched is None:
                outcome = "unknown"
            else:
                outcome = "success" if matched.get("exit_code", 1) == 0 else "failure"
            runs.append({
                "id": run_id,
                "type": _semantic_type(kind, matched),
                "started_at": started.isoformat(),
                "outcome": outcome,
                "spend_usd": spend_by_date.get(started.strftime("%Y-%m-%d %H:%M"), 0.0),
            })
        return runs

    def last_run(self):
        runs = self.recent_runs(limit=1)
        return runs[0] if runs else None

    def _usage_entries(self):
        path = self.logs / "usage-tracker.jsonl"
        entries = []
        if not path.exists():
            return entries
        for line in path.read_text().splitlines():
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            ts_local = rec.get("ts_local")
            parsed = _parse_local(ts_local)
            if parsed is not None:
                entries.append((parsed, rec))
        return entries

    def _match_usage(self, started, usage):
        """Nearest usage-tracker record at-or-after the run's start, within 4h."""
        best = None
        for ts, rec in usage:
            if started <= ts <= started + _OUTCOME_WINDOW:
                if best is None or ts < best[0]:
                    best = (ts, rec)
        return best[1] if best else None

    # -- running -------------------------------------------------------------
    def running(self):
        lock = self.logs / ".scout-session.lock"
        if not lock.exists():
            return {"active": False}
        try:
            pid = int(lock.read_text().strip())
        except (ValueError, OSError):
            return {"active": False}
        if not _pid_alive(pid):
            return {"active": False}
        raw = self._runs_raw()
        result = {"active": True, "pid": pid}
        if raw:
            _run_id, kind, started = raw[0]  # the live run is the newest log
            matched = self._match_usage(started, self._usage_entries())
            result["type"] = _semantic_type(kind, matched)
            result["started_at"] = started.isoformat()
        return result

    # -- spend ---------------------------------------------------------------
    def _spend_by_local_minute(self):
        """cost_usd keyed by 'YYYY-MM-DD HH:MM' (ET local) for run correlation."""
        out = {}
        path = self.logs / "session-tokens.jsonl"
        if not path.exists():
            return out
        for line in path.read_text().splitlines():
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            parsed = _parse_local(rec.get("ts_et"))
            if parsed is not None:
                out[parsed.strftime("%Y-%m-%d %H:%M")] = rec.get("cost_usd", 0.0)
        return out

    def today_spend_usd(self):
        today = self.now.strftime("%Y-%m-%d")
        path = self.logs / "session-tokens.jsonl"
        total = 0.0
        if not path.exists():
            return total
        for line in path.read_text().splitlines():
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            ts_et = rec.get("ts_et", "")
            if ts_et.startswith(today):
                total += float(rec.get("cost_usd", 0.0) or 0.0)
        return round(total, 2)

    # -- schedule ------------------------------------------------------------
    def next_run(self):
        path = self.vault / ".scout-state" / "schedule.yaml"
        if not path.exists():
            return None
        slots = _parse_schedule_slots(path.read_text())
        best = None
        for slot in slots:
            fire = _next_fire(self.now, slot["weekdays"], slot["fires_at_local"])
            if fire is None:
                continue
            if best is None or fire < best[0]:
                best = (fire, slot)
        if best is None:
            return None
        fire, slot = best
        return {
            "slot": slot["name"],
            "type": slot["type"],
            "fires_at_local": slot["fires_at_local"],
            "fires_at": fire.isoformat(),
        }

    # -- connectors ----------------------------------------------------------
    def connectors(self):
        health = self.vault / "knowledge-base" / "connector-health.md"
        out = []
        if not health.exists():
            return out
        for line in health.read_text().splitlines():
            row = [c.strip() for c in line.split("|")[1:-1]] if line.strip().startswith("|") else None
            if not row or len(row) < 2:
                continue
            name = row[0]
            if name in ("Connector", "") or set(name) <= set("-:"):
                continue  # header / separator
            rate = row[-1]
            out.append({"name": name, "status": "unknown" if rate == "—" else "ok"})
        dark = self._recently_alerted_connectors()
        for c in out:
            if c["name"] in dark:
                c["status"] = "dark"
        return out

    def _recently_alerted_connectors(self):
        path = self.logs / "connector-alerts.log"
        dark = set()
        if not path.exists():
            return dark
        for line in path.read_text().splitlines():
            ts = _parse_alert_ts(line)
            if ts is None or self.now - ts > _ALERT_WINDOW:
                continue
            for known in _ALERT_NAME_HINTS:
                if known.lower() in line.lower():
                    dark.add(known)
        return dark

    # -- assemble ------------------------------------------------------------
    def status(self):
        return {
            "running": self.running(),
            "last_run": self.last_run(),
            "next_run": self.next_run(),
            "today_spend_usd": self.today_spend_usd(),
            "connectors": self.connectors(),
            "recent_runs": self.recent_runs(),
        }


# Connector display names the alert log may reference (matches connector-health.md rows).
_ALERT_NAME_HINTS = [
    "Slack", "Linear", "Gmail", "Google Calendar", "Granola", "Fathom",
    "Google Drive", "GitHub", "Chrome (Google Messages)", "Google Messages", "WhatsApp",
]


def _semantic_type(filename_kind, matched_usage):
    """Run-scout.sh logs are all `scout-*` regardless of briefing/consolidation;
    the real mode is in the correlated usage-tracker `type`. Fall back to the
    filename prefix (right for research/dreaming, and for a still-running scout
    session whose usage record isn't written yet)."""
    if matched_usage and matched_usage.get("type"):
        return matched_usage["type"]
    return filename_kind


def _pid_alive(pid):
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    return True


def _parse_local(text):
    """Parse 'YYYY-MM-DD HH:MM <TZ>' (e.g. '2026-06-13 11:05 EDT') → naive datetime."""
    if not text:
        return None
    parts = text.split()
    if len(parts) < 2:
        return None
    try:
        return datetime.strptime(parts[0] + " " + parts[1], "%Y-%m-%d %H:%M")
    except ValueError:
        return None


def _parse_alert_ts(line):
    """Alert lines start with 'YYYY-MM-DD HH:MM ...'."""
    return _parse_local(line)


def _parse_schedule_slots(text):
    """Minimal schedule.yaml slot parser (avoids a PyYAML dependency)."""
    slots = []
    current = None
    in_slots = False
    for raw in text.splitlines():
        if raw.rstrip().startswith("#"):
            continue
        if re.match(r"^slots:\s*$", raw):
            in_slots = True
            continue
        if not in_slots:
            continue
        m_name = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", raw)
        if m_name:
            current = {"name": m_name.group(1), "type": None,
                       "fires_at_local": None, "weekdays": []}
            slots.append(current)
            continue
        if current is None:
            continue
        m_kv = re.match(r"^    ([a-z_]+):\s*(.+)$", raw)
        if not m_kv:
            continue
        key, val = m_kv.group(1), m_kv.group(2).strip()
        if key == "type":
            current["type"] = val
        elif key == "fires_at_local":
            current["fires_at_local"] = val.strip('"').strip("'")
        elif key == "weekdays":
            current["weekdays"] = [d.strip() for d in val.strip("[]").split(",") if d.strip()]
    return [s for s in slots if s["fires_at_local"] and s["weekdays"]]


def _next_fire(now, weekdays, fires_at_local):
    """Soonest datetime > now whose weekday is in `weekdays` at HH:MM."""
    try:
        hh, mm = (int(x) for x in fires_at_local.split(":"))
    except ValueError:
        return None
    allowed = {WEEKDAYS.index(d) for d in weekdays if d in WEEKDAYS}
    if not allowed:
        return None
    for offset in range(0, 8):
        cand = (now + timedelta(days=offset)).replace(hour=hh, minute=mm, second=0, microsecond=0)
        if cand.weekday() in allowed and cand > now:
            return cand
    return None
