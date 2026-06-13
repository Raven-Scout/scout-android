"""RunLauncher — spawns Scout runner scripts on demand for the phone Control
Center. Pre-flight (already-running? over-budget?) is pure and tested; the spawn
and budget-check are injected so tests stay hermetic. Runs are detached so a
bridge restart never kills an in-flight Scout session. See spec §3, §9."""

import json
import os
import re
import subprocess
from pathlib import Path

RUN_ID_RE = re.compile(r"^[a-z]+-\d{4}-\d{2}-\d{2}_\d{2}-\d{2}$")

# Phone "run now" type → runner script + whether it needs SCOUT_FORCE_MODE.
TYPE_RUNNERS = {
    "consolidation": ("run-scout.sh", "consolidation"),
    "briefing": ("run-scout.sh", "briefing"),
    "research": ("run-research.sh", None),
    "dreaming": ("run-dreaming.sh", None),
}


def _default_spawn(argv, env):
    proc = subprocess.Popen(
        argv, env=env, start_new_session=True,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return proc.pid


def _default_budget_check():
    try:
        return subprocess.run(["scoutctl", "budget", "check"], timeout=30).returncode
    except (OSError, subprocess.SubprocessError):
        return 0  # budget telemetry hiccup → don't hard-block (spec §6)


class RunLauncher:
    def __init__(self, vault, spawn=_default_spawn, budget_check=_default_budget_check):
        self.vault = Path(vault)
        self.spawn = spawn
        self.budget_check = budget_check

    def run(self, run_type, bypass_budget=False):
        mapping = TYPE_RUNNERS.get(run_type)
        if mapping is None:
            return {"started": False, "reason": "unknown_type"}
        runner, force_mode = mapping
        return self._launch(runner, force_mode, bypass_budget, retry_of=None)

    def retry(self, run_id, bypass_budget=False):
        if not RUN_ID_RE.match(run_id or ""):
            return {"started": False, "reason": "bad_run_id"}
        run_type = self.lookup_type(run_id)
        if run_type is None or run_type not in TYPE_RUNNERS:
            return {"started": False, "reason": "not_found"}
        runner, force_mode = TYPE_RUNNERS[run_type]
        return self._launch(runner, force_mode, bypass_budget, retry_of=run_id)

    def _launch(self, runner, force_mode, bypass_budget, retry_of):
        if self._running():
            return {"started": False, "reason": "already_running"}
        if not bypass_budget and self.budget_check() != 0:
            return {"started": False, "reason": "over_budget"}
        env = dict(os.environ)
        if force_mode:
            env["SCOUT_FORCE_MODE"] = force_mode
        if bypass_budget:
            env["SCOUT_BYPASS_BUDGET"] = "1"
        if retry_of:
            env["SCOUT_RETRY_OF"] = retry_of
        pid = self.spawn([str(self.vault / runner)], env)
        return {"started": True, "pid": pid}

    def _running(self):
        lock = self.vault / ".scout-logs" / ".scout-session.lock"
        if not lock.exists():
            return False
        try:
            pid = int(lock.read_text().strip())
            os.kill(pid, 0)
        except (ValueError, OSError):
            return False
        return True

    def lookup_type(self, run_id):
        """Recorded mode for a finished run, from usage-tracker / session-tokens.
        Correlates by the run_id's local HH:MM (the log basename is local time)."""
        m = re.match(r"^[a-z]+-(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})$", run_id)
        if not m:
            return None
        y, mo, d, hh, mm = m.groups()
        day = f"{y}-{mo}-{d}"
        logs = self.vault / ".scout-logs"
        # usage-tracker.type is the semantic mode; match the nearest entry that
        # day at-or-after the run's start minute.
        usage = logs / "usage-tracker.jsonl"
        if usage.exists():
            best = None
            start_minute = int(hh) * 60 + int(mm)
            for line in usage.read_text().splitlines():
                try:
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    continue
                ts_local = rec.get("ts_local", "")
                if not ts_local.startswith(day):
                    continue
                parts = ts_local.split()
                if len(parts) < 2:
                    continue
                try:
                    ehh, emm = (int(x) for x in parts[1].split(":"))
                except ValueError:
                    continue
                emin = ehh * 60 + emm
                if emin >= start_minute and (best is None or emin < best[0]):
                    best = (emin, rec.get("type"))
            if best and best[1]:
                # usage-tracker type may be a slot label (e.g. "midday-consolidation")
                return _normalize_type(best[1])
        return None


def _normalize_type(raw):
    """Map a usage-tracker type/slot label to a TYPE_RUNNERS key."""
    raw = (raw or "").lower()
    for key in TYPE_RUNNERS:
        if key in raw:
            return key
    return None
