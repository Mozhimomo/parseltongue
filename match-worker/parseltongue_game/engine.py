"""Server-authoritative, simultaneous-turn snake simulation (standard library only)."""

from collections import Counter
from dataclasses import asdict, dataclass, replace
import json
import random

DIRECTIONS = {"UP": (0, -1), "RIGHT": (1, 0), "DOWN": (0, 1), "LEFT": (-1, 0)}
OPPOSITE = {"UP": "DOWN", "DOWN": "UP", "LEFT": "RIGHT", "RIGHT": "LEFT"}
FAULTS = {"AI_ERROR", "INVALID_ACTION", "REVERSE", "MISSING_ACTION"}
PROTOCOL = "snake-v1"
RULESET = "survival-hunger-v1"


@dataclass(frozen=True)
class Rules:
    width: int = 20
    height: int = 20
    initial_length: int = 3
    food_count: int = 5
    hunger_max: int = 100
    hunger_decay: int = 1
    food_restore: int = 40
    max_ticks: int = 2000

    def __post_init__(self):
        for value in asdict(self).values():
            if type(value) is not int:
                raise ValueError("Rules must contain integers")
        if not (8 <= self.width <= 40 and 8 <= self.height <= 40):
            raise ValueError("Board dimensions must be 8..40")
        if not 2 <= self.initial_length <= min(self.width, self.height) - 5:
            raise ValueError("Initial snake length does not fit spawn positions")
        if not 0 <= self.food_count <= self.width * self.height - 4 * self.initial_length:
            raise ValueError("Invalid food count")
        if not (1 <= self.hunger_decay <= self.hunger_max <= 1000):
            raise ValueError("Invalid hunger budget")
        if not 1 <= self.food_restore <= self.hunger_max:
            raise ValueError("Invalid food recovery")
        if not 1 <= self.max_ticks <= 2000:
            raise ValueError("Turn limit must be 1..2000")


@dataclass(frozen=True)
class Snake:
    id: str
    body: tuple[tuple[int, int], ...]
    direction: str
    hunger: int
    alive: bool = True
    death_reason: str | None = None
    death_tick: int | None = None


class Game:
    def __init__(self, rules: Rules | None = None, seed: int = 1,
                 snakes: tuple[Snake, ...] | None = None,
                 food: set[tuple[int, int]] | None = None):
        self.rules = rules or Rules()
        if type(seed) is not int or not 0 <= seed <= 2**31 - 1:
            raise ValueError("Seed must be a nonnegative 32-bit integer")
        self.seed = seed
        self._random = random.Random(seed)
        self.tick = 0
        self.snakes = tuple(snakes) if snakes is not None else self._spawn()
        self.food = set(food) if food is not None else set()
        self.result = None
        self._validate_initial_state()
        if food is None:
            self._refill_food()

    def _spawn(self):
        r = self.rules
        bodies = [
            tuple((r.initial_length + 1 - i, 2) for i in range(r.initial_length)),
            tuple((r.width - 3, r.initial_length + 1 - i) for i in range(r.initial_length)),
            tuple((r.width - r.initial_length - 2 + i, r.height - 3) for i in range(r.initial_length)),
            tuple((2, r.height - r.initial_length - 2 + i) for i in range(r.initial_length)),
        ]
        return tuple(Snake(f"s{i + 1}", body, direction, r.hunger_max)
                     for i, (body, direction) in enumerate(zip(bodies, ("RIGHT", "DOWN", "LEFT", "UP"))))

    def _validate_initial_state(self):
        if len(self.snakes) != 4 or len({s.id for s in self.snakes}) != 4:
            raise ValueError("A match needs exactly four unique snakes")
        occupied = set()
        for snake in self.snakes:
            if (type(snake.id) is not str or not snake.id or not snake.body
                    or snake.direction not in DIRECTIONS
                    or not snake.alive or snake.death_reason is not None or snake.death_tick is not None
                    or type(snake.hunger) is not int or not 1 <= snake.hunger <= self.rules.hunger_max):
                raise ValueError("Invalid initial snake")
            for index, cell in enumerate(snake.body):
                if not self._inside(cell) or cell in occupied:
                    raise ValueError("Invalid or overlapping body")
                if index and sum(abs(a - b) for a, b in zip(cell, snake.body[index - 1])) != 1:
                    raise ValueError("Body cells must be adjacent")
                occupied.add(cell)
            if len(snake.body) > 1:
                dx, dy = DIRECTIONS[snake.direction]
                if (snake.body[1][0] + dx, snake.body[1][1] + dy) != snake.body[0]:
                    raise ValueError("Direction must point away from the neck")
        if any(not self._inside(cell) or cell in occupied for cell in self.food):
            raise ValueError("Food must occupy free board cells")

    def _inside(self, cell):
        return (len(cell) == 2 and all(type(n) is int for n in cell)
                and 0 <= cell[0] < self.rules.width and 0 <= cell[1] < self.rules.height)

    def _refill_food(self):
        occupied = {cell for s in self.snakes if s.alive for cell in s.body} | self.food
        free = [(x, y) for y in range(self.rules.height) for x in range(self.rules.width)
                if (x, y) not in occupied]
        for _ in range(min(max(0, self.rules.food_count - len(self.food)), len(free))):
            self.food.add(free.pop(self._random.randrange(len(free))))

    def snapshot(self):
        # JSON-compatible copies only: no live engine references cross the AI boundary.
        return {"tick": self.tick, "food": [list(p) for p in sorted(self.food)],
                "snakes": [{"id": s.id, "body": [list(p) for p in s.body],
                            "direction": s.direction, "hunger": s.hunger,
                            "hunger_max": self.rules.hunger_max, "alive": s.alive,
                            "death_reason": s.death_reason, "death_tick": s.death_tick}
                           for s in self.snakes]}

    def observation(self, snake_id):
        state = self.snapshot()
        own = next((s for s in state["snakes"] if s["id"] == snake_id and s["alive"]), None)
        if own is None:
            raise ValueError("No living snake with that ID")
        return json.loads(json.dumps({"protocol_version": PROTOCOL, "ruleset_version": RULESET,
            "tick": self.tick, "max_ticks": self.rules.max_ticks,
            "board": {"width": self.rules.width, "height": self.rules.height, "obstacles": []},
            "self": own, "snakes": state["snakes"], "food": state["food"],
            "hunger_rules": {"decay": self.rules.hunger_decay, "food_restore": self.rules.food_restore}}))

    def step(self, actions: dict, failures: dict | None = None):
        if self.result is not None:
            raise ValueError("Match has already ended")
        living = {s.id: s for s in self.snakes if s.alive}
        failures = failures or {}
        if set(actions) - living.keys() or set(failures) - living.keys():
            raise ValueError("Actions may target only assigned living snakes")
        if any(reason not in FAULTS for reason in failures.values()):
            raise ValueError("Unknown adapter failure")
        dead, intended, commands = {}, {}, {}
        for sid, snake in living.items():
            action = actions.get(sid)
            reason = failures.get(sid)
            if reason is None:
                if sid not in actions:
                    reason = "MISSING_ACTION"
                elif type(action) is not str or action not in DIRECTIONS:
                    reason = "INVALID_ACTION"
                elif action == OPPOSITE[snake.direction]:
                    reason = "REVERSE"
            commands[sid] = {"direction": action if type(action) is str and action in DIRECTIONS else None,
                             "error": reason}
            if reason:
                dead[sid] = reason
            else:
                dx, dy = DIRECTIONS[action]
                intended[sid] = (snake.body[0][0] + dx, snake.body[0][1] + dy)

        occupied = set()
        for sid, snake in living.items():
            # Failed snakes keep their body for this turn. Growth retains the tail.
            body = snake.body if sid in dead or intended[sid] in self.food else snake.body[:-1]
            occupied.update(body)
        head_counts = Counter(intended.values())
        swapped = {sid for sid, head in intended.items() for other, other_head in intended.items()
                   if sid != other and head == living[other].body[0] and other_head == living[sid].body[0]}
        for sid, head in intended.items():
            if not self._inside(head):
                dead[sid] = "WALL"
            elif head_counts[head] > 1:
                dead[sid] = "HEAD_ON"
            elif sid in swapped:
                dead[sid] = "HEAD_SWAP"
            elif head in occupied:
                dead[sid] = "BODY"

        self.tick += 1
        updated, consumed, events = [], set(), []
        for snake in self.snakes:
            if not snake.alive:
                updated.append(snake)
                continue
            sid = snake.id
            if sid not in dead:
                ate = intended[sid] in self.food
                hunger = min(self.rules.hunger_max, snake.hunger - self.rules.hunger_decay
                             + (self.rules.food_restore if ate else 0))
                body = (intended[sid],) + (snake.body if ate else snake.body[:-1])
                snake = replace(snake, direction=actions[sid], body=body, hunger=max(0, hunger))
                if hunger <= 0:
                    dead[sid] = "STARVATION"
                elif ate:
                    consumed.add(intended[sid])
                    events.append({"snake_id": sid, "type": "ATE", "position": list(intended[sid])})
            if sid in dead:
                snake = replace(snake, alive=False, death_reason=dead[sid], death_tick=self.tick)
                events.append({"snake_id": sid, "type": "DIED", "reason": dead[sid]})
            updated.append(snake)
        self.snakes = tuple(updated)
        self.food -= consumed
        self._refill_food()
        survivors = [s.id for s in self.snakes if s.alive]
        if len(survivors) <= 1:
            winner = survivors[0] if survivors else None
            outcomes = {s.id: ("WIN" if s.id == winner else
                              "DRAW" if winner is None and s.death_tick == self.tick
                              and s.death_reason not in FAULTS else "LOSS") for s in self.snakes}
            self.result = {"reason": "LAST_SURVIVOR" if winner else "ALL_DEAD",
                           "winner_id": winner, "ticks": self.tick, "outcomes": outcomes}
        elif self.tick >= self.rules.max_ticks:
            self.result = {"reason": "NO_CONTEST", "winner_id": None, "ticks": self.tick,
                           "outcomes": {s.id: "NO_CONTEST" for s in self.snakes}}
        return {"state": self.snapshot(), "commands": commands, "events": events}
