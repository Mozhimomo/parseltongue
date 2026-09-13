"""Entire validation or match runs in this disposable E2B VM."""
from contextlib import ExitStack
from dataclasses import replace
import json
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from parseltongue_game.engine import Game, Rules, FAULTS, DIRECTIONS, OPPOSITE
from parseltongue_game.runner import run_match


class Rejected(Exception):
    def __init__(self, code, stage="decision"):
        self.code, self.stage = code, stage
        super().__init__(code)


class Agent:
    def __init__(self, specification):
        self.process = None
        self.last_error = None
        self.selector = selectors.DefaultSelector()
        try:
            self.process = subprocess.Popen([sys.executable, "-I", str(Path(__file__).with_name("agent.py"))],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                start_new_session=True, env={"PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8"})
            self.selector.register(self.process.stdout, selectors.EVENT_READ)
            self.send(specification)
            if self.receive(3) != {"ready": True}:
                raise Rejected("LOAD_ERROR", "load")
        except Exception:
            self.close()
            raise Rejected("LOAD_ERROR", "load") from None

    def send(self, value):
        self.process.stdin.write((json.dumps(value, separators=(",", ":")) + "\n").encode())
        self.process.stdin.flush()

    def receive(self, seconds):
        deadline = time.monotonic() + seconds
        data = bytearray()
        while len(data) <= 1024:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not self.selector.select(remaining):
                raise Rejected("TIMEOUT")
            chunk = os.read(self.process.stdout.fileno(), 1025 - len(data))
            if not chunk:
                raise Rejected("PROCESS_EXIT")
            data.extend(chunk)
            if b"\n" in data:
                return json.loads(data)
        raise Rejected("OUTPUT_LIMIT")

    def decide(self, observation):
        try:
            self.send(observation)
            result = self.receive(1.25)
            if result.get("error") in ("AI_ERROR", "INVALID_ACTION"):
                raise Rejected(result["error"])
            action = result.get("action")
            if type(action) is not str or action not in DIRECTIONS:
                raise Rejected("INVALID_ACTION")
            if action == OPPOSITE[observation["self"]["direction"]]:
                raise Rejected("REVERSE")
            return action
        except Exception as error:
            self.last_error = error if isinstance(error, Rejected) else Rejected("AI_ERROR")
            self.close()
            raise self.last_error from None

    def close(self):
        if self.process is not None:
            process, self.process = self.process, None
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=2)
            try:
                process.stdin.close()
            except BrokenPipeError:
                pass
            process.stdout.close()
        self.selector.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def match(job):
    with ExitStack() as stack:
        agents = {}
        for i, name in enumerate(job["agents"]):
            sid = f"s{i+1}"
            specification = {"source": job["strategies"][sid]["source"]} if sid in job["strategies"] else {"builtin": name}
            try:
                agents[sid] = stack.enter_context(Agent(specification)).decide
            except Rejected:
                def failed(_):
                    raise Rejected("LOAD_ERROR", "load")
                agents[sid] = failed
        return run_match(job["agents"], job["seed"], Rules(max_ticks=job["maxTicks"]), agents)


def validate(source):
    tests = []
    for seat in range(4):
        for food, hunger, width in [(5, 100, 20), (0, 1, 8), (1, 2, 40)]:
            game = Game(Rules(width=width, height=width, food_count=food), seed=42)
            game.snakes = tuple(replace(s, hunger=hunger) if i == seat else s for i, s in enumerate(game.snakes))
            for dead in (False, True):
                observation = game.observation(f"s{seat+1}")
                observation["snakes"][(seat+1) % 4]["alive"] = not dead
                with Agent({"source": source}) as agent:
                    agent.decide(observation)
                tests.append({"name": f"seat={seat+1},board={width},food={food},hunger={hunger},dead={dead}", "passed": True})
        for seed in (17, 42, 93):
            names = ["forager", "cautious", "greedy", "forager"]
            names[seat] = "generated"
            # Each trial gets fresh state; globals persist for the duration of a match.
            with Agent({"source": source}) as agent:
                replay = run_match(names, seed, Rules(max_ticks=200), {f"s{seat+1}": agent.decide})
                reason = replay["frames"][-1]["state"]["snakes"][seat]["death_reason"]
                if reason in FAULTS:
                    raise Rejected(agent.last_error.code if agent.last_error else reason, "trial")
            tests.append({"name": f"trial-seat={seat+1},seed={seed}", "passed": True})
    return {"accepted": True, "testsPassed": len(tests), "tests": tests}


def main():
    # Guard against accidentally running this entry point on the business host.
    if sys.platform != "linux" or os.environ.get("PARSELTONGUE_E2B_JOB") != "1":
        raise RuntimeError("This runner is only for the E2B sandbox")
    try:
        job = json.loads(Path("/home/user/parseltongue/input.json").read_text())
        result = validate(job["source"]) if job["mode"] == "validate" else match(job)
    except Rejected as error:
        result = {"accepted": False, "stage": error.stage, "code": error.code, "error": str(error)}
    except Exception:
        result = {"accepted": False, "stage": "sandbox", "code": "RUNTIME_ERROR", "error": "Sandbox runtime failed", "infrastructure": True}
    print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
