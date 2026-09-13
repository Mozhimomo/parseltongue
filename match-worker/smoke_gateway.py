"""Integration smoke test. Creates a test account on the supplied development gateway."""

import argparse
import json
import secrets
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from parseltongue_game.runner import verify_replay


def main():
    parser = argparse.ArgumentParser(description="Test a running development gateway/auth/arena stack")
    parser.add_argument("--gateway", default="http://localhost:8080")
    args = parser.parse_args()
    base = args.gateway.rstrip("/")

    def call(path, method="GET", body=None, token=None, expected=200):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                          headers=headers, method=method)
        try:
            response = urlopen(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            payload = json.loads(response.read())
            if response.status != expected:
                raise RuntimeError(f"{method} {path}: expected {expected}, got {response.status}: {payload.get('message')}")
            return payload.get("data")

    call("/api/game/agents", expected=401)
    account = {"username": "game_test_" + secrets.token_hex(4), "password": secrets.token_urlsafe(18)}
    call("/api/auth/register", "POST", account, expected=201)
    token = call("/api/auth/login", "POST", account)["accessToken"]
    try:
        assert len(call("/api/game/agents", token=token)) == 4
        task = call("/api/game/trials", "POST", {"agents": ["straight", "cautious", "greedy", "forager"],
                    "seed": 42, "maxTicks": 500}, token, expected=202)
        deadline = time.monotonic() + 40
        while task["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
            time.sleep(.2)
            task = call("/api/game/trials/" + task["id"], token=token)
        if task["status"] != "SUCCEEDED":
            raise RuntimeError("Trial did not complete: " + task["status"])
        replay = call("/api/game/trials/" + task["id"] + "/replay", token=token)
        verify_replay(replay)
        print("Gateway -> auth -> arena -> Python -> replay verification: passed")
        print(json.dumps(replay["result"]))
    finally:
        call("/api/auth/logout", "POST", token=token)
    call("/api/game/agents", token=token, expected=401)
    print("Revoked session rejected by arena: passed")


if __name__ == "__main__":
    main()
