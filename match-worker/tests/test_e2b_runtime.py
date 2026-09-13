"""Linux integration with reviewed fixtures only. Never load saved player snakes."""
import importlib.util
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

from parseltongue_game.engine import Game
from parseltongue_game.runner import verify_replay


@unittest.skipUnless(sys.platform == "linux", "Sandbox child runtime requires Linux")
class RuntimeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="e2b-runtime-fixtures-")
        root = Path(__file__).resolve().parents[1]
        target = Path(cls.directory.name)
        for name in ("job.py", "agent.py"):
            shutil.copyfile(root / "e2b_runtime" / name, target / name)
        (target / "parseltongue_game").mkdir()
        for name in ("__init__", "engine", "runner", "stubs"):
            shutil.copyfile(root / "parseltongue_game" / f"{name}.py", target / "parseltongue_game" / f"{name}.py")
        spec = importlib.util.spec_from_file_location("fixture_runtime", target / "job.py")
        cls.runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.runtime)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_standard_library_strategy_passes_all_checks(self):
        source = "from collections import deque\ndef decide(o):\n    return deque([o['self']['direction']]).pop()\n"
        self.assertEqual(self.runtime.validate(source)["testsPassed"], 36)

    def test_prompt_code_example_runs_the_documented_move_and_all_36_checks(self):
        import json
        prompts = Path(__file__).resolve().parents[2] / "arena-service/src/main/resources/prompts"
        source = (prompts / "strategy-example.py").read_text()
        observation = json.loads((prompts / "strategy-observation-example.json").read_text())
        with self.runtime.Agent({"source": source}) as agent:
            self.assertEqual(agent.decide(observation), "RIGHT")
        self.assertEqual(self.runtime.validate(source)["testsPassed"], 36)

    def test_state_persists_during_match_and_resets_for_new_process(self):
        source = "counter=0\ndef decide(o):\n    global counter\n    counter+=1\n    return 'RIGHT' if counter==1 else 'DOWN'\n"
        for _ in range(2):
            with self.runtime.Agent({"source": source}) as agent:
                observation = Game().observation("s1")
                self.assertEqual(agent.decide(observation), "RIGHT")
                self.assertEqual(agent.decide(observation), "DOWN")

    def test_bad_results_load_errors_and_loops_fail_with_bounded_execution(self):
        for source in ("def decide(o): return None", "def decide(o): return 'LEFT'",
                       "def decide(o):\n    while True: pass", "def decide(o): raise Exception('fixture')",
                       "raise RuntimeError('fixture load')", "while True: pass"):
            with self.subTest(source=source), self.assertRaises(self.runtime.Rejected):
                with self.runtime.Agent({"source": source}) as agent:
                    agent.decide(Game().observation("s1"))

    def test_print_cannot_corrupt_protocol(self):
        source = "print('loading fixture')\ndef decide(o):\n    print('noise'*1000)\n    return o['self']['direction']\n"
        with self.runtime.Agent({"source": source}) as agent:
            self.assertEqual(agent.decide(Game().observation("s1")), "RIGHT")

    def test_memory_budget_terminates_fixture_and_new_process_recovers(self):
        source = "def decide(o):\n    data=bytearray(400*1024*1024)\n    return 'RIGHT'\n"
        with self.assertRaises(self.runtime.Rejected):
            with self.runtime.Agent({"source": source}) as agent:
                agent.decide(Game().observation("s1"))
        with self.runtime.Agent({"builtin": "straight"}) as agent:
            self.assertEqual(agent.decide(Game().observation("s1")), "RIGHT")

    def test_four_persistent_processes_complete_verified_match(self):
        source = "def decide(o): return o['self']['direction']"
        job = {"agents": ["generated"]*4, "seed": 42, "maxTicks": 30,
               "strategies": {f"s{i}": {"source": source} for i in range(1, 5)}}
        self.assertTrue(verify_replay(self.runtime.match(job)))

    def test_one_crashing_snake_does_not_abort_other_players(self):
        job = {"agents": ["generated", "forager", "cautious", "greedy"], "seed": 42, "maxTicks": 30,
               "strategies": {"s1": {"source": "def decide(o): return None"}}}
        replay = self.runtime.match(job)
        self.assertTrue(verify_replay(replay))
        self.assertEqual(replay["frames"][-1]["state"]["snakes"][0]["death_reason"], "AI_ERROR")
