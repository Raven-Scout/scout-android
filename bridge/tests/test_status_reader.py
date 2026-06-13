import os
import json
import unittest
import tempfile
from pathlib import Path
from datetime import datetime
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from status_reader import StatusReader  # noqa: E402


def build_vault(tmp):
    v = Path(tmp)
    logs = v / ".scout-logs"
    logs.mkdir(parents=True)
    (logs / "scout-2026-06-13_08-00.log").write_text("morning briefing\n")
    (logs / "scout-2026-06-13_10-56.log").write_text("consolidation\n")
    (logs / "research-2026-06-12_22-30.log").write_text("research\n")
    (logs / "launchd-heartbeat.log").write_text("not a run\n")  # must be ignored
    with (logs / "usage-tracker.jsonl").open("w") as f:
        f.write(json.dumps({"ts_local": "2026-06-13 08:12 EDT", "type": "briefing",
                            "exit_code": 0, "source": "runner"}) + "\n")
        f.write(json.dumps({"ts_local": "2026-06-13 11:05 EDT", "type": "consolidation",
                            "exit_code": 1, "source": "runner"}) + "\n")
    return v


class RunsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.v = build_vault(self.tmp.name)
        self.now = datetime(2026, 6, 13, 12, 0)

    def tearDown(self):
        self.tmp.cleanup()

    def test_recent_runs_parsed_from_filenames_newest_first(self):
        runs = StatusReader(self.v, now=self.now).recent_runs()
        self.assertEqual(runs[0]["id"], "scout-2026-06-13_10-56")
        self.assertEqual(runs[0]["type"], "consolidation")
        self.assertEqual([r["id"] for r in runs][:3],
                         ["scout-2026-06-13_10-56", "scout-2026-06-13_08-00",
                          "research-2026-06-12_22-30"])

    def test_launchd_and_nonrun_logs_excluded(self):
        ids = [r["id"] for r in StatusReader(self.v, now=self.now).recent_runs()]
        self.assertNotIn("launchd-heartbeat", ids)

    def test_outcome_from_usage_tracker_nearest_after(self):
        runs = {r["id"]: r for r in StatusReader(self.v, now=self.now).recent_runs()}
        self.assertEqual(runs["scout-2026-06-13_08-00"]["outcome"], "success")
        self.assertEqual(runs["scout-2026-06-13_10-56"]["outcome"], "failure")
        self.assertEqual(runs["research-2026-06-12_22-30"]["outcome"], "unknown")

    def test_running_false_without_live_lock(self):
        self.assertEqual(StatusReader(self.v, now=self.now).running(), {"active": False})

    def test_running_true_with_live_lock_pid(self):
        lock = self.v / ".scout-logs" / ".scout-session.lock"
        lock.write_text(str(os.getpid()))
        st = StatusReader(self.v, now=self.now).running()
        self.assertTrue(st["active"])
        self.assertEqual(st["pid"], os.getpid())
        self.assertEqual(st["type"], "consolidation")  # newest log

    def test_running_false_with_stale_lock_pid(self):
        (self.v / ".scout-logs" / ".scout-session.lock").write_text("999999")
        self.assertFalse(StatusReader(self.v, now=self.now).running()["active"])


class StatusFieldsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        v = Path(self.tmp.name)
        self.v = v
        logs = v / ".scout-logs"
        logs.mkdir(parents=True)
        with (logs / "session-tokens.jsonl").open("w") as f:
            f.write(json.dumps({"ts_et": "2026-06-13 08:12 EDT", "cost_usd": 3.50}) + "\n")
            f.write(json.dumps({"ts_et": "2026-06-13 11:05 EDT", "cost_usd": 9.00}) + "\n")
            f.write(json.dumps({"ts_et": "2026-06-12 22:40 EDT", "cost_usd": 5.00}) + "\n")  # yesterday
        (logs / "connector-alerts.log").write_text("2026-06-13 06:01 ET  Granola went dark\n")
        (v / ".scout-state").mkdir()
        (v / ".scout-state" / "schedule.yaml").write_text(
            "slots:\n"
            "  afternoon-consolidation:\n    type: consolidation\n    runner: run-scout.sh\n"
            "    fires_at_local: \"17:00\"\n    weekdays: [Mon, Tue, Wed, Thu, Fri]\n"
            "  dreaming-evening:\n    type: dreaming\n    runner: run-dreaming.sh\n"
            "    fires_at_local: \"18:30\"\n    weekdays: [Mon, Tue, Wed, Thu, Fri, Sat, Sun]\n")
        (v / "knowledge-base").mkdir()
        (v / "knowledge-base" / "connector-health.md").write_text(
            "| Connector | x | 7d OK rate |\n|---|---|---|\n"
            "| Slack | ✅ 2 | 100% (36/36) |\n"
            "| Granola | · | 100% (4/4) |\n"
            "| WhatsApp | · | — |\n")
        self.now = datetime(2026, 6, 13, 12, 0)  # Saturday

    def tearDown(self):
        self.tmp.cleanup()

    def test_today_spend_sums_today_et_only(self):
        self.assertAlmostEqual(StatusReader(self.v, now=self.now).today_spend_usd(), 12.50)

    def test_next_run_is_soonest_future_slot(self):
        nr = StatusReader(self.v, now=self.now).next_run()  # Sat 12:00 → dreaming runs Sat
        self.assertEqual(nr["slot"], "dreaming-evening")
        self.assertEqual(nr["fires_at_local"], "18:30")

    def test_connectors_ok_unknown_dark(self):
        c = {x["name"]: x["status"] for x in StatusReader(self.v, now=self.now).connectors()}
        self.assertEqual(c["Slack"], "ok")        # rate present, no alert
        self.assertEqual(c["WhatsApp"], "unknown")  # rate —
        self.assertEqual(c["Granola"], "dark")     # recent alert overrides rate

    def test_status_assembles_all_keys(self):
        s = StatusReader(self.v, now=self.now).status()
        self.assertTrue(set(s) >= {"running", "last_run", "next_run",
                                   "today_spend_usd", "connectors", "recent_runs"})


if __name__ == "__main__":
    unittest.main()
