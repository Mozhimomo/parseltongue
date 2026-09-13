"""Offline/local prototype runner for the four reviewed built-in strategies only."""

from dataclasses import asdict
from .engine import Game, Rules, PROTOCOL, RULESET
from .stubs import STUBS


def run_match(names=("straight", "cautious", "greedy", "forager"), seed=1, rules=None, custom_agents=None):
    custom_agents = custom_agents or {}
    if (set(custom_agents) - {"s1", "s2", "s3", "s4"} or len(names) != 4
            or any(name not in STUBS and f"s{i + 1}" not in custom_agents for i, name in enumerate(names))):
        raise ValueError("Choose exactly four built-in AI names")
    game = Game(rules, seed)
    assignments = {s.id: name for s, name in zip(game.snakes, names)}
    replay = {"protocol_version": PROTOCOL, "ruleset_version": RULESET, "seed": seed,
              "rules": asdict(game.rules), "agents": assignments,
              "frames": [{"state": game.snapshot(), "commands": {}, "events": []}]}
    while game.result is None:
        # Capture all observations before any AI runs or any state advances.
        observations = {s.id: game.observation(s.id) for s in game.snakes if s.alive}
        actions, failures = {}, {}
        for sid, observation in observations.items():
            try:
                actions[sid] = (custom_agents[sid] if sid in custom_agents else STUBS[assignments[sid]])(observation)
            except Exception:
                failures[sid] = "AI_ERROR"
        replay["frames"].append(game.step(actions, failures))
    replay["result"] = game.result
    return replay


def verify_replay(replay):
    """Re-simulate actual logged actions, without invoking any AI."""
    if replay["protocol_version"] != PROTOCOL or replay["ruleset_version"] != RULESET:
        raise ValueError("Unsupported replay version")
    game = Game(Rules(**replay["rules"]), replay["seed"])
    frames = replay["frames"]
    if not frames or frames[0] != {"state": game.snapshot(), "commands": {}, "events": []}:
        raise ValueError("Initial state mismatch")
    for frame in frames[1:]:
        actions = {sid: c["direction"] for sid, c in frame["commands"].items()}
        failures = {sid: c["error"] for sid, c in frame["commands"].items() if c["error"]}
        if game.step(actions, failures) != frame:
            raise ValueError("Replay frame mismatch")
    if game.result is None or game.result != replay["result"]:
        raise ValueError("Replay result mismatch")
    return True
