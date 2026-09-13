"""Linux-only child entry point, uploaded to E2B; never launched by the host."""
import contextlib
import json
import os
from pathlib import Path
import resource
import signal
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from parseltongue_game.stubs import STUBS


def main():
    resource.setrlimit(resource.RLIMIT_AS, (256 * 1024**2,) * 2)
    resource.setrlimit(resource.RLIMIT_FSIZE, (1024**2,) * 2)
    resource.setrlimit(resource.RLIMIT_NOFILE, (64, 64))
    resource.setrlimit(resource.RLIMIT_NPROC, (64, 64))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    # OS timers terminate instead of raising a catchable Python exception.
    signal.signal(signal.SIGPROF, signal.SIG_DFL)
    signal.signal(signal.SIGALRM, signal.SIG_DFL)
    request = json.loads(sys.stdin.buffer.readline(262145))
    protocol = os.fdopen(os.dup(1), "w", buffering=1)
    with open(os.devnull, "w") as sink:
        os.dup2(sink.fileno(), 1)
        os.dup2(sink.fileno(), 2)
        with contextlib.redirect_stdout(sink), contextlib.redirect_stderr(sink):
            signal.setitimer(signal.ITIMER_REAL, 2)
            if "source" in request:
                namespace = {"__name__": "snake_strategy"}
                exec(compile(request["source"], "<strategy>", "exec"), namespace)
                decide = namespace["decide"]
            else:
                decide = STUBS[request["builtin"]]
            if not callable(decide):
                raise ValueError("decide must be callable")
            signal.setitimer(signal.ITIMER_REAL, 0)
            protocol.write('{"ready":true}\n')
            for line in sys.stdin.buffer:
                observation = json.loads(line)
                signal.setitimer(signal.ITIMER_REAL, 1)
                signal.setitimer(signal.ITIMER_PROF, .2)
                try:
                    action = decide(observation)
                    result = {"action": action} if type(action) is str and len(action) <= 5 else {"error": "INVALID_ACTION"}
                except BaseException:
                    result = {"error": "AI_ERROR"}
                finally:
                    signal.setitimer(signal.ITIMER_PROF, 0)
                    signal.setitimer(signal.ITIMER_REAL, 0)
                protocol.write(json.dumps(result) + "\n")


if __name__ == "__main__":
    main()
