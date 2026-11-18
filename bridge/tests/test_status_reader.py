import os
import json
import unittest
import tempfile
from pathlib import Path
from datetime import datetime
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from status_reader import StatusReader, is_lock_skip, parse_run_id  # noqa: E402


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


LOCK_SKIP = "=== Another Scout session running (PID 4242) — skipping after a 600s wait ===\n"
LOCK_WAIT = "=== Another Scout session running (PID 4242) — waiting up to 600s for the lock ===\n"


class RunIdTest(unittest.TestCase):
    """Run-log names since Raven-Scout/Scout#673: the minute name, or -SS-<PID>."""

    def test_minute_name_keeps_its_id_and_minute(self):
        self.assertEqual(parse_run_id("scout-2026-11-13_17-57"),
                         ("scout", datetime(2026, 11, 13, 17, 57), None))

    def test_fallback_name_adds_seconds_and_pid(self):
        self.assertEqual(parse_run_id("dreaming-2026-11-13_17-57-30-48213"),
                         ("dreaming", datetime(2026, 11, 13, 17, 57, 30), 48213))

    def test_malformed_names_rejected(self):
        for bad in ["scout-2026-11-13_17-57-30",       # seconds without a PID
                    "scout-2026-11-13_17-57-30-",      # empty PID
                    "scout-2026-11-13_17-57-3-48213",  # one-digit seconds
                    "scout-2026-11-13_17-57\n",        # trailing newline
                    "Scout-2026-11-13_17-57",          # stem is lowercase
                    "scout-2026-13-13_17-57",          # no month 13
                    "launchd-heartbeat", "", None]:
            self.assertIsNone(parse_run_id(bad), bad)

    def test_recent_runs_ids_are_log_basenames_in_both_forms(self):
        with tempfile.TemporaryDirectory() as tmp:
            logs = Path(tmp) / ".scout-logs"
            logs.mkdir()
            (logs / "scout-2026-11-13_17-57.log").write_text("x\n")
            (logs / "scout-2026-11-13_17-57-30-48213.log").write_text("x\n")
            runs = {r["id"]: r for r in StatusReader(tmp, now=datetime(2026, 11, 13, 19)).recent_runs()}
        self.assertEqual(set(runs), {"scout-2026-11-13_17-57", "scout-2026-11-13_17-57-30-48213"})
        self.assertEqual(runs["scout-2026-11-13_17-57"]["started_at"], "2026-11-13T17:57:00")
        self.assertEqual(runs["scout-2026-11-13_17-57-30-48213"]["started_at"], "2026-11-13T17:57:30")

    def test_sort_is_minute_then_second_then_pid(self):
        names = [
            "scout-2026-11-13_17-56-59-1",
            "scout-2026-11-13_17-57",             # claimed the minute: its first run
            "scout-2026-11-13_17-57-05-100000",
            "scout-2026-11-13_17-57-30-9999",     # text order would put this after 48213
            "scout-2026-11-13_17-57-30-48213",
            "research-2026-11-13_17-58",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            logs = Path(tmp) / ".scout-logs"
            logs.mkdir()
            for n in names:
                (logs / f"{n}.log").write_text("x\n")
            ids = [r["id"] for r in StatusReader(tmp, now=datetime(2026, 11, 13, 19)).recent_runs()]
        self.assertEqual(ids, list(reversed(names)))


class LockSkipTest(unittest.TestCase):
    """A run the session lock turned away must not displace the live run."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.v = Path(self.tmp.name)
        self.logs = self.v / ".scout-logs"
        self.logs.mkdir()
        self.now = datetime(2026, 11, 13, 18, 30)
        with (self.logs / "usage-tracker.jsonl").open("w") as f:
            # The live run's record, written when it finishes; a skip in its
            # minute must not borrow it.
            f.write(json.dumps({"ts_local": "2026-11-13 18:20 EST", "type": "consolidation",
                                "exit_code": 0, "source": "runner"}) + "\n")
        (self.logs / "session-tokens.jsonl").write_text(
            json.dumps({"ts_et": "2026-11-13 17:57 EST", "cost_usd": 4.0}) + "\n")

    def tearDown(self):
        self.tmp.cleanup()

    def lock(self, pid):
        (self.logs / ".scout-session.lock").write_text(str(pid))

    def test_waiting_line_alone_is_not_a_skip(self):
        (self.logs / "scout-2026-11-13_17-57.log").write_text(LOCK_WAIT + "session output\n")
        (self.logs / "scout-2026-11-13_17-58.log").write_text(LOCK_WAIT + LOCK_SKIP)
        self.assertFalse(is_lock_skip(self.logs / "scout-2026-11-13_17-57.log"))
        self.assertTrue(is_lock_skip(self.logs / "scout-2026-11-13_17-58.log"))
        self.assertFalse(is_lock_skip(self.logs / "missing.log"))

    def test_same_minute_skip_leaves_live_run_running(self):
        (self.logs / "scout-2026-11-13_17-57.log").write_text("session output\n")
        (self.logs / "scout-2026-11-13_17-57-30-48213.log").write_text(LOCK_SKIP)
        self.lock(os.getpid())
        reader = StatusReader(self.v, now=self.now)
        live = reader.running()
        self.assertEqual(live["run_id"], "scout-2026-11-13_17-57")
        self.assertEqual(live["started_at"], "2026-11-13T17:57:00")
        runs = {r["id"]: r for r in reader.recent_runs()}
        self.assertEqual(runs["scout-2026-11-13_17-57"]["outcome"], "running")
        skip = runs["scout-2026-11-13_17-57-30-48213"]
        self.assertEqual(skip["outcome"], "skipped")
        self.assertEqual(skip["spend_usd"], 0.0)  # the minute's spend is the live run's
        self.assertEqual(skip["type"], "scout")   # not the live run's usage type
        self.assertEqual(reader.last_run()["id"], "scout-2026-11-13_17-57-30-48213")

    def test_later_minute_skip_leaves_live_run_running(self):
        (self.logs / "scout-2026-11-13_17-57.log").write_text("session output\n")
        (self.logs / "dreaming-2026-11-13_18-05.log").write_text(LOCK_SKIP)
        self.lock(os.getpid())
        reader = StatusReader(self.v, now=self.now)
        self.assertEqual(reader.running()["run_id"], "scout-2026-11-13_17-57")
        outcomes = {r["id"]: r["outcome"] for r in reader.recent_runs()}
        self.assertEqual(outcomes, {"scout-2026-11-13_17-57": "running",
                                    "dreaming-2026-11-13_18-05": "skipped"})

    def test_fallback_name_matching_lock_pid_is_the_live_run(self):
        pid = os.getpid()
        (self.logs / "scout-2026-11-13_17-57.log").write_text("exit code: 0\n")
        (self.logs / f"scout-2026-11-13_17-57-40-{pid}.log").write_text("session output\n")
        # Newer, still waiting for the lock: not a skip yet, but not the holder.
        (self.logs / "research-2026-11-13_17-59.log").write_text(LOCK_WAIT)
        self.lock(pid)
        self.assertEqual(StatusReader(self.v, now=self.now).running()["run_id"],
                         f"scout-2026-11-13_17-57-40-{pid}")

    def test_skip_without_live_lock_is_skipped_not_success(self):
        (self.logs / "scout-2026-11-13_17-57.log").write_text("exit code: 0\n")
        (self.logs / "scout-2026-11-13_17-57-30-48213.log").write_text(LOCK_SKIP)
        runs = {r["id"]: r for r in StatusReader(self.v, now=self.now).recent_runs()}
        self.assertEqual(runs["scout-2026-11-13_17-57"]["outcome"], "success")
        self.assertEqual(runs["scout-2026-11-13_17-57"]["spend_usd"], 4.0)
        self.assertEqual(runs["scout-2026-11-13_17-57-30-48213"]["outcome"], "skipped")


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
