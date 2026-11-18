import os
import unittest
import tempfile
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import json  # noqa: E402
from run_launcher import RUN_ID_RE, RunLauncher  # noqa: E402


class LaunchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.v = Path(self.tmp.name)
        (self.v / ".scout-logs").mkdir(parents=True)
        self.spawned = []
        self.launcher = RunLauncher(
            self.v,
            spawn=lambda argv, env: self.spawned.append((argv, env)) or 4321,
            budget_check=lambda: 0,  # 0 = proceed
        )

    def tearDown(self):
        self.tmp.cleanup()

    def test_run_consolidation_spawns_run_scout_with_force_mode(self):
        r = self.launcher.run("consolidation")
        self.assertTrue(r["started"])
        argv, env = self.spawned[0]
        self.assertTrue(argv[0].endswith("run-scout.sh"))
        self.assertEqual(env["SCOUT_FORCE_MODE"], "consolidation")
        self.assertNotIn("SCOUT_BYPASS_BUDGET", env)

    def test_run_research_uses_research_runner_no_force_mode(self):
        self.launcher.run("research")
        argv, env = self.spawned[0]
        self.assertTrue(argv[0].endswith("run-research.sh"))
        self.assertNotIn("SCOUT_FORCE_MODE", env)

    def test_unknown_type_rejected_no_spawn(self):
        r = self.launcher.run("garbage")
        self.assertFalse(r["started"])
        self.assertEqual(r["reason"], "unknown_type")
        self.assertEqual(self.spawned, [])

    def test_already_running_blocks(self):
        (self.v / ".scout-logs" / ".scout-session.lock").write_text(str(os.getpid()))
        r = self.launcher.run("consolidation")
        self.assertFalse(r["started"])
        self.assertEqual(r["reason"], "already_running")
        self.assertEqual(self.spawned, [])

    def test_over_budget_blocks_unless_bypass(self):
        self.launcher.budget_check = lambda: 1  # skip
        self.assertEqual(self.launcher.run("consolidation")["reason"], "over_budget")
        self.assertEqual(self.spawned, [])
        r = self.launcher.run("consolidation", bypass_budget=True)
        self.assertTrue(r["started"])
        self.assertEqual(self.spawned[0][1]["SCOUT_BYPASS_BUDGET"], "1")

    def test_retry_validates_id_and_sets_retry_of(self):
        self.assertEqual(self.launcher.retry("bad id")["reason"], "bad_run_id")
        self.launcher.lookup_type = lambda rid: "consolidation"
        r = self.launcher.retry("scout-2026-06-13_10-56")
        self.assertTrue(r["started"])
        self.assertEqual(self.spawned[-1][1]["SCOUT_RETRY_OF"], "scout-2026-06-13_10-56")
        self.assertEqual(self.spawned[-1][1]["SCOUT_FORCE_MODE"], "consolidation")

    def test_retry_unknown_id_not_found(self):
        self.launcher.lookup_type = lambda rid: None
        self.assertEqual(self.launcher.retry("scout-2026-06-13_10-56")["reason"], "not_found")


    def test_retry_accepts_fallback_id_verbatim(self):
        self.launcher.lookup_type = lambda rid: "research"
        r = self.launcher.retry("research-2026-11-13_17-57-30-48213")
        self.assertTrue(r["started"])
        self.assertEqual(self.spawned[-1][1]["SCOUT_RETRY_OF"], "research-2026-11-13_17-57-30-48213")

    def test_retry_rejects_malformed_ids(self):
        for bad in ["scout-2026-11-13_17-57-30", "scout-2026-11-13_17-57\n", "../scout-2026-11-13_17-57"]:
            self.assertEqual(self.launcher.retry(bad)["reason"], "bad_run_id", bad)
        self.assertEqual(self.spawned, [])

    def test_run_id_re_fullmatch_both_forms(self):
        # scout_bridge guards GET /api/v1/runs/<id>/log with this.
        self.assertTrue(RUN_ID_RE.fullmatch("scout-2026-11-13_17-57"))
        self.assertTrue(RUN_ID_RE.fullmatch("scout-2026-11-13_17-57-30-48213"))
        self.assertIsNone(RUN_ID_RE.fullmatch("scout-2026-11-13_17-57\n"))
        self.assertIsNone(RUN_ID_RE.fullmatch("bad..id"))


class LookupTypeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.logs = Path(self.tmp.name) / ".scout-logs"
        self.logs.mkdir()
        (self.logs / "usage-tracker.jsonl").write_text(
            json.dumps({"ts_local": "2026-11-13 17:50 EST", "type": "briefing"}) + "\n"
            + json.dumps({"ts_local": "2026-11-13 18:20 EST", "type": "midday-consolidation"}) + "\n")
        self.launcher = RunLauncher(self.tmp.name, spawn=lambda a, e: 1, budget_check=lambda: 0)

    def tearDown(self):
        self.tmp.cleanup()

    def test_minute_and_fallback_ids_correlate_by_start_minute(self):
        (self.logs / "scout-2026-11-13_17-57-30-48213.log").write_text("exit code: 1\n")
        self.assertEqual(self.launcher.lookup_type("scout-2026-11-13_17-57"), "consolidation")
        self.assertEqual(self.launcher.lookup_type("scout-2026-11-13_17-57-30-48213"), "consolidation")
        self.assertEqual(self.launcher.lookup_type("scout-2026-11-13_17-45"), "briefing")

    def test_lock_skipped_run_has_no_type(self):
        (self.logs / "scout-2026-11-13_17-57-30-48213.log").write_text(
            "=== Another Scout session running (PID 4242) — skipping after a 600s wait ===\n")
        self.assertIsNone(self.launcher.lookup_type("scout-2026-11-13_17-57-30-48213"))
        self.assertIsNone(self.launcher.lookup_type("scout-2026-11-13_17-57-30"))


if __name__ == "__main__":
    unittest.main()
