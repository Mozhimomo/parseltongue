"""Fixed, trusted test doubles. This registry is NOT a user-script loader or sandbox."""

from collections import deque
from .engine import DIRECTIONS, OPPOSITE


def straight(observation: dict) -> str:
    """Never turn: useful for predictable wall deaths."""
    return observation["self"]["direction"]


def _options(observation):
    own = observation["self"]
    blocked = {tuple(p) for snake in observation["snakes"] if snake["alive"] for p in snake["body"]}
    # Conservative: do not assume an opponent will vacate its tail.
    blocked.discard(tuple(own["body"][-1]))
    width, height = observation["board"]["width"], observation["board"]["height"]
    head = own["body"][0]
    return [(direction, (head[0] + dx, head[1] + dy)) for direction, (dx, dy) in DIRECTIONS.items()
            if direction != OPPOSITE[own["direction"]]
            and 0 <= head[0] + dx < width and 0 <= head[1] + dy < height
            and (head[0] + dx, head[1] + dy) not in blocked]


def cautious(observation: dict) -> str:
    """Keep heading when safe, otherwise choose the first unoccupied direction."""
    options = _options(observation)
    return next((d for d, _ in options if d == observation["self"]["direction"]),
                options[0][0] if options else straight(observation))


def greedy(observation: dict) -> str:
    """Move toward the closest food using Manhattan distance."""
    options = _options(observation)
    food = observation["food"]
    if not options or not food:
        return cautious(observation)
    return min(options, key=lambda option: min(abs(option[1][0] - f[0]) + abs(option[1][1] - f[1])
                                                for f in food))[0]


def forager(observation: dict) -> str:
    """Breadth-first food search; avoid reachable opposing heads and prefer open space."""
    options = _options(observation)
    if not options:
        return straight(observation)
    width, height = observation["board"]["width"], observation["board"]["height"]
    occupied = {tuple(p) for s in observation["snakes"] if s["alive"] for p in s["body"]}
    occupied.discard(tuple(observation["self"]["body"][-1]))
    food = {tuple(p) for p in observation["food"]}
    threats = set()
    for snake in observation["snakes"]:
        if snake["alive"] and snake["id"] != observation["self"]["id"]:
            for d, (dx, dy) in DIRECTIONS.items():
                if d != OPPOSITE[snake["direction"]]:
                    threats.add((snake["body"][0][0] + dx, snake["body"][0][1] + dy))

    def score(option):
        _, start = option
        queue, visited = deque([(start, 0)]), {start}
        distance = width * height + 1
        while queue:
            cell, steps = queue.popleft()
            if cell in food:
                distance = min(distance, steps + 1)
            for dx, dy in DIRECTIONS.values():
                nxt = (cell[0] + dx, cell[1] + dy)
                if (0 <= nxt[0] < width and 0 <= nxt[1] < height
                        and nxt not in occupied and nxt not in visited):
                    visited.add(nxt)
                    queue.append((nxt, steps + 1))
        return (start in threats, len(visited) < len(observation["self"]["body"]) + 2,
                distance, -len(visited))

    return min(options, key=score)[0]


STUBS = {"straight": straight, "cautious": cautious, "greedy": greedy, "forager": forager}
