"""Run: python match-worker/run_match.py --seed 42 --output replay.json"""

import argparse
import json
from pathlib import Path
import sys

# Fixed trusted package root, including when Java launches Python in isolated (-I) mode.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from parseltongue_game.engine import Rules
from parseltongue_game.runner import run_match, verify_replay
from parseltongue_game.stubs import STUBS


def main():
    parser = argparse.ArgumentParser(description="Four-snake simulation with built-in trusted AI stubs")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--ais", nargs=4, choices=STUBS, default=list(STUBS))
    parser.add_argument("--max-ticks", type=int, default=2000)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify", type=Path, help="Verify a replay without running AI")
    args = parser.parse_args()
    try:
        if args.verify:
            if args.verify.stat().st_size > 32 * 1024 * 1024:
                raise ValueError("Replay exceeds 32 MiB")
            verify_replay(json.loads(args.verify.read_text(encoding="utf-8")))
            print("Replay verified")
            return
        replay = run_match(args.ais, args.seed, Rules(max_ticks=args.max_ticks))
        serialized = json.dumps(replay, ensure_ascii=True, separators=(",", ":"))
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(serialized, encoding="utf-8")
            print(json.dumps(replay["result"]))
        else:
            print(serialized)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
