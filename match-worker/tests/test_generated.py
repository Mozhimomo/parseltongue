"""Offline E2B transport tests; opt-in cloud smoke test uses fixed source only."""
import ast
import copy
import json
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
import uuid
from unittest.mock import MagicMock, patch

import strategy_job as controller
from parseltongue_game.engine import Rules
from parseltongue_game.runner import run_match

GOOD = "def decide(o):\n    return o['self']['direction']\n"
CHECKS = {"accepted": True, "testsPassed": 36, "tests": [{"name": str(i), "passed": True} for i in range(36)]}


def match_job():
    return {"mode": "match", "seed": 42, "maxTicks": 30,
            "agents": ["snake:test", "cautious", "greedy", "forager"],
            "strategies": {"s1": {"source": GOOD, "name": "Fixture snake", "owner": "private"}}}


def replay_fixture():
    # This fixture runs reviewed built-ins only, never the uploaded source.
    result = run_match(["straight", "cautious", "greedy", "forager"], 42, Rules(max_ticks=30))
    result["agents"]["s1"] = "snake:test"
    return result


class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.job_id = uuid.uuid4().hex
        self.env = patch.dict(os.environ, {"E2B_API_KEY": "host-only-test-key", "E2B_TEMPLATE": "base"})
        self.env.start()
        self.factory = MagicMock()
        self.vm = self.factory.create.return_value
        self.vm.sandbox_id = "test-vm"
        self.sdk = patch.object(controller, "sdk", return_value=self.factory)
        self.sdk.start()
        self.vm.commands.run.return_value = SimpleNamespace(exit_code=0, stdout=json.dumps(CHECKS))

    def tearDown(self):
        self.sdk.stop()
        self.env.stop()
        controller.journal(self.job_id).unlink(missing_ok=True)

    def test_one_vm_per_candidate_no_host_secrets_and_cleanup(self):
        result = controller.execute({"mode": "validate", "source": GOOD}, self.job_id)
        self.assertTrue(result["accepted"])
        self.assertEqual(result["policy"], controller.POLICY)
        self.factory.create.assert_called_once()
        opts = self.factory.create.call_args.kwargs
        self.assertFalse(opts["allow_internet_access"])
        self.assertFalse(opts["network"]["allow_public_traffic"])
        self.assertTrue(opts["secure"])
        self.assertEqual(opts["lifecycle"], {"on_timeout": "kill"})
        self.assertEqual(opts["timeout"], 120)
        self.assertEqual(opts["envs"], {})
        self.assertNotIn("host-only-test-key", str(self.vm.files.write.call_args_list))
        self.assertNotIn("host-only-test-key", str(self.vm.commands.run.call_args_list))
        self.vm.commands.run.assert_called_once()
        self.vm.kill.assert_called_once()
        self.assertFalse(controller.journal(self.job_id).exists())

    def test_entire_four_snake_match_uses_one_remote_command_and_verifies(self):
        self.vm.commands.run.return_value.stdout = json.dumps(replay_fixture())
        result = controller.execute(match_job(), self.job_id)
        self.assertEqual(result["agent_names"], {"s1": "Fixture snake"})
        self.factory.create.assert_called_once()
        self.vm.commands.run.assert_called_once()
        uploaded = json.loads(self.vm.files.write.call_args.args[1])
        self.assertNotIn("owner", uploaded["strategies"]["s1"])
        self.assertNotIn("name", uploaded["strategies"]["s1"])

    def test_upload_timeout_command_failure_and_malformed_output_always_kill(self):
        for stage in ("upload", "command", "json"):
            with self.subTest(stage=stage):
                self.vm.reset_mock()
                self.vm.files.write.side_effect = TimeoutError() if stage == "upload" else None
                self.vm.commands.run.side_effect = TimeoutError() if stage == "command" else None
                self.vm.commands.run.return_value.stdout = "not json"
                with self.assertRaises(Exception):
                    controller.execute({"mode": "validate", "source": GOOD}, self.job_id)
                self.vm.kill.assert_called_once()

    def test_output_budget_aborts_and_destroys_vm(self):
        def flood(*args, **opts):
            for _ in range(9):
                opts["on_stdout"]("x" * 1024**2)
        self.vm.commands.run.side_effect = flood
        with self.assertRaises(controller.InfrastructureError):
            controller.execute({"mode": "validate", "source": GOOD}, self.job_id)
        self.vm.kill.assert_called_once()

    def test_failed_cleanup_retains_id_for_external_retry(self):
        self.vm.kill.side_effect = TimeoutError()
        with self.assertRaises(TimeoutError):
            controller.execute({"mode": "validate", "source": GOOD}, self.job_id)
        self.assertTrue(controller.journal(self.job_id).exists())
        controller.cleanup_job(self.job_id)
        self.factory.kill.assert_called_once_with("test-vm", api_key="host-only-test-key", request_timeout=10)
        self.assertFalse(controller.journal(self.job_id).exists())

    def test_create_failure_never_uploads_or_falls_back(self):
        self.factory.create.side_effect = TimeoutError()
        with self.assertRaises(TimeoutError):
            controller.execute({"mode": "validate", "source": GOOD}, self.job_id)
        self.vm.files.write.assert_not_called()
        self.vm.commands.run.assert_not_called()

    def test_missing_key_stops_before_cloud_or_source_execution(self):
        self.sdk.stop()
        with patch.dict(os.environ, {"E2B_API_KEY": ""}):
            with self.assertRaises(controller.InfrastructureError):
                controller.execute({"mode": "preflight"}, self.job_id)
        self.factory.create.assert_not_called()

    def test_tampered_seed_rules_frames_assignments_or_result_rejected(self):
        for field in ("seed", "rules", "frames", "agents", "result"):
            result = replay_fixture()
            if field == "seed": result[field] = 12
            elif field == "rules": result[field]["max_ticks"] = 29
            elif field == "frames": result[field][1]["state"]["tick"] = 99
            elif field == "agents": result[field]["s1"] = "forager"
            else: result[field] = {}
            with self.subTest(field=field), self.assertRaises(ValueError):
                controller.checked_result(match_job(), result, "base")

    def test_incomplete_checks_and_unknown_fields_do_not_escape(self):
        result = copy.deepcopy(CHECKS)
        result["tests"].pop()
        with self.assertRaises(ValueError):
            controller.checked_result({"mode": "validate", "source": GOOD}, result, "base")
        result = controller.checked_result({"mode": "validate"}, {"accepted": False, "source": GOOD}, "base")
        self.assertNotIn("source", result)

    def test_host_entry_points_never_execute_generated_python(self):
        for filename in ("strategy_job.py", "generated_job.py"):
            tree = ast.parse((controller.ROOT / filename).read_text())
            self.assertFalse(any(isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
                and n.func.id in {"exec", "eval", "compile"} for n in ast.walk(tree)))


@unittest.skipUnless(os.environ.get("RUN_E2B_TESTS") == "1", "Set RUN_E2B_TESTS=1 and E2B_API_KEY for cloud smoke test")
class LiveE2BTests(unittest.TestCase):
    def test_validate_and_match_in_separate_disposable_vms(self):
        result = controller.execute({"mode": "validate", "source": GOOD}, uuid.uuid4().hex)
        self.assertTrue(result["accepted"], result)
        result = controller.execute(match_job(), uuid.uuid4().hex)
        self.assertIn("result", result)
        self.assertEqual(result["agent_names"]["s1"], "Fixture snake")
