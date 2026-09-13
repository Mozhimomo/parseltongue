DIRECTIONS = {"UP": (0, -1), "RIGHT": (1, 0), "DOWN": (0, 1), "LEFT": (-1, 0)}
OPPOSITE = {"UP": "DOWN", "DOWN": "UP", "LEFT": "RIGHT", "RIGHT": "LEFT"}


def decide(observation):
    own = observation["self"]
    current = own["direction"]
    width = observation["board"]["width"]
    height = observation["board"]["height"]
    head_x, head_y = own["body"][0]
    food = {tuple(point) for point in observation["food"]}
    own_body = {tuple(point) for point in own["body"]}
    others = {tuple(point) for snake in observation["snakes"]
              if snake["alive"] and snake["id"] != own["id"]
              for point in snake["body"]}
    tail = tuple(own["body"][-1])
    order = [current] + [d for d in DIRECTIONS if d != current]
    candidates = []
    for direction in order:
        if direction == OPPOSITE[current]:
            continue
        dx, dy = DIRECTIONS[direction]
        target = (head_x + dx, head_y + dy)
        if not (0 <= target[0] < width and 0 <= target[1] < height):
            continue
        self_collision = target in own_body and not (target == tail and target not in food)
        if self_collision or target in others:
            continue
        distance = min((abs(target[0] - x) + abs(target[1] - y) for x, y in food), default=0)
        candidates.append((distance, len(candidates), direction))
    return min(candidates)[2] if candidates else current
