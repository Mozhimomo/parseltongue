"""Trusted E2B controller. Generated Python is data here, never executable code."""
import argparse
from dataclasses import asdict
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
from parseltongue_game.engine import Rules
from parseltongue_game.runner import verify_replay
from parseltongue_game.stubs import STUBS

ROOT = Path(__file__).resolve().parent
REMOTE = "/home/user/parseltongue"
POLICY = "e2b-match-v1"
MAX_OUTPUT = 8 * 1024 * 1024
TTL = 120
COMMAND = "python3 -I /home/user/parseltongue/job.py"


class InfrastructureError(Exception):
    pass


def sdk():
    if not os.environ.get("E2B_API_KEY", "").strip():
        raise InfrastructureError("E2B_API_KEY is not configured")
    try:
        from e2b import Sandbox
        return Sandbox
    except ImportError:
        raise InfrastructureError("Install match-worker/requirements.txt in GAME_PYTHON") from None


def journal(job_id):
    if not re.fullmatch(r"[a-f0-9]{32}", job_id):
        raise ValueError("Invalid job identifier")
    return Path(tempfile.gettempdir()) / f"parseltongue-e2b-{job_id}.json"


def cleanup_job(job_id):
    path = journal(job_id)
    if path.exists():
        sandbox_id = json.loads(path.read_text())["sandbox_id"]
        sdk().kill(sandbox_id, api_key=os.environ["E2B_API_KEY"], request_timeout=10)
        path.unlink(missing_ok=True)


def check_source(source):
    if not isinstance(source, str) or not 0 < len(source.encode()) <= 24 * 1024:
        raise ValueError("Invalid source size")


def check_job(job):
    if job.get("mode") == "validate":
        check_source(job["source"])
    elif job.get("mode") == "match":
        Rules(max_ticks=job["maxTicks"])
        if type(job["seed"]) is not int or not 0 <= job["seed"] <= 2**31-1:
            raise ValueError("Invalid seed")
        if len(job["agents"]) != 4 or not isinstance(job["strategies"], dict):
            raise ValueError("Invalid assignments")
        if set(job["strategies"]) - {"s1", "s2", "s3", "s4"}:
            raise ValueError("Invalid seats")
        for i, name in enumerate(job["agents"]):
            strategy = job["strategies"].get(f"s{i+1}")
            if strategy is not None:
                check_source(strategy["source"])
            elif name not in STUBS:
                raise ValueError("Unknown built-in")
    else:
        raise ValueError("Unknown mode")


def checked_result(job, result, template):
    if not isinstance(result, dict):
        raise ValueError("Invalid result")
    if result.get("accepted") is False:
        return {"accepted": False, "stage": str(result.get("stage", "sandbox"))[:40],
                "code": str(result.get("code", "EXECUTION_FAILED"))[:60],
                "error": str(result.get("error", "Execution failed"))[:500],
                "infrastructure": result.get("infrastructure") is True}
    if job["mode"] == "validate":
        tests = result.get("tests")
        if result.get("accepted") is not True or result.get("testsPassed") != 36 or not isinstance(tests, list) or len(tests) != 36:
            raise ValueError("Incomplete validation")
        if any(not isinstance(t, dict) or t.get("passed") is not True or not isinstance(t.get("name"), str) for t in tests):
            raise ValueError("Invalid checks")
        return {"accepted": True, "testsPassed": 36,
                "tests": [{"name": t["name"][:160], "passed": True} for t in tests],
                "sha256": hashlib.sha256(job["source"].encode()).hexdigest(),
                "policy": POLICY, "image": template, "runtime": "e2b"}
    expected_agents = {f"s{i+1}": name for i, name in enumerate(job["agents"])}
    if (result.get("rules") != asdict(Rules(max_ticks=job["maxTicks"]))
            or result.get("seed") != job["seed"] or result.get("agents") != expected_agents
            or not isinstance(result.get("frames"), list)
            or not 2 <= len(result["frames"]) <= job["maxTicks"] + 1):
        raise ValueError("Replay does not match submitted game")
    verify_replay(result)
    replay = {key: result[key] for key in ("protocol_version", "ruleset_version", "seed", "rules", "agents", "frames", "result")}
    replay["agent_names"] = {sid: data["name"] for sid, data in job["strategies"].items()}
    return replay


def execute(job, job_id):
    Sandbox = sdk()
    if job.get("mode") == "preflight":
        # Local readiness only; cloud outages are infrastructure failures, not
        # strategy defects to send back to the model for repair.
        return {"accepted": True, "policy": POLICY, "runtime": "e2b"}
    check_job(job)
    path = journal(job_id)
    template = os.environ.get("E2B_TEMPLATE", "base").strip() or "base"
    sandbox = None
    try:
        sandbox = Sandbox.create(template=template, timeout=TTL, secure=True,
            allow_internet_access=False, network={"allow_public_traffic": False},
            lifecycle={"on_timeout": "kill"}, metadata={"app": "parseltongue", "job": job_id},
            envs={}, api_key=os.environ["E2B_API_KEY"], request_timeout=15)
        # Save ID before uploading generated code. Java can clean up after killing
        # this controller; the platform TTL also covers lost create responses.
        path.write_text(json.dumps({"sandbox_id": sandbox.sandbox_id}))
        uploads = [("e2b_runtime/job.py", "job.py"), ("e2b_runtime/agent.py", "agent.py")] + [
            (f"parseltongue_game/{name}.py", f"parseltongue_game/{name}.py")
            for name in ("__init__", "engine", "runner", "stubs")]
        for local, remote in uploads:
            sandbox.files.write(f"{REMOTE}/{remote}", (ROOT / local).read_bytes(), request_timeout=10)
        # Only game input is uploaded. No owner IDs, validation metadata, gateway
        # tokens, database settings or host filesystem are passed to the VM.
        payload = {"mode": job["mode"], "source": job["source"]} if job["mode"] == "validate" else {
            "mode": "match", "agents": job["agents"], "seed": job["seed"], "maxTicks": job["maxTicks"],
            "strategies": {sid: {"source": data["source"]} for sid, data in job["strategies"].items()}}
        sandbox.files.write(f"{REMOTE}/input.json", json.dumps(payload), request_timeout=10)
        sizes = [0, 0]
        def limited(index, limit):
            def accept(chunk):
                sizes[index] += len(chunk.encode())
                if sizes[index] > limit:
                    raise InfrastructureError("Sandbox output limit exceeded")
            return accept
        output = sandbox.commands.run(COMMAND, cwd=REMOTE, user="user",
            envs={"PARSELTONGUE_E2B_JOB": "1"}, timeout=80, request_timeout=10,
            on_stdout=limited(0, MAX_OUTPUT), on_stderr=limited(1, 65536))
        if output.exit_code != 0 or len(output.stdout.encode()) > MAX_OUTPUT:
            raise InfrastructureError("Sandbox command failed")
        return checked_result(job, json.loads(output.stdout), template)
    finally:
        if sandbox is not None:
            sandbox.kill(request_timeout=10)
            path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", nargs="?", choices=("validate", "match", "cleanup", "preflight"))
    parser.add_argument("--job-id", default=uuid.uuid4().hex)
    args = parser.parse_args()
    if args.mode == "cleanup":
        cleanup_job(args.job_id)
        return
    try:
        raw = sys.stdin.buffer.read(262145)
        if len(raw) > 262144:
            raise ValueError("Job input too large")
        job = json.loads(raw)
        if args.mode:
            job["mode"] = args.mode
        result = execute(job, args.job_id)
    except InfrastructureError as error:
        result = {"accepted": False, "stage": "infrastructure", "code": "E2B_UNAVAILABLE", "error": str(error), "infrastructure": True}
    except Exception as error:
        # SDK exception text may contain URLs/tokens; only record the class.
        result = {"accepted": False, "stage": "coordinator", "code": "E2B_JOB_FAILED", "error": type(error).__name__, "infrastructure": True}
    print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
