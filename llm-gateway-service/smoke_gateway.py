"""Explicit live smoke test: caller -> internal gateway -> LLM gateway -> provider.

Requires GENERATION_SERVICE_TOKEN. Never needs or sends a provider API key.
Makes one short paid request per registered tier; not run by Maven tests.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="http://127.0.0.1:8084")
    args = parser.parse_args()
    token = os.environ.get("GENERATION_SERVICE_TOKEN")
    if not token:
        raise SystemExit("Set GENERATION_SERVICE_TOKEN before running this explicit live test.")

    def call(path, body=None):
        request = urllib.request.Request(
            args.gateway.rstrip("/") + path,
            data=None if body is None else json.dumps(body).encode("utf-8"),
            headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                result = json.load(response)
                if result.get("code") != 0:
                    raise RuntimeError("Gateway returned an unsuccessful envelope")
                return result["data"]
        except urllib.error.HTTPError as error:
            # Do not print request headers or arbitrary upstream error bodies.
            raise RuntimeError("Gateway HTTP status " + str(error.code)) from None

    registered = {model["model"] for model in call("/internal/llm/models")}
    for alias in ("low-cost", "high-capability"):
        if alias not in registered:
            raise RuntimeError("Missing registered tier: " + alias)
        result = call("/internal/llm/generations", {
            "taskId": "smoke-" + uuid.uuid4().hex,
            "model": alias,
            "messages": [{"role": "user", "content": "Reply with exactly OK."}],
            "maxOutputTokens": 32,
        })
        if result["model"] != alias or not result.get("content", "").strip():
            raise RuntimeError("Invalid generation response for " + alias)
        print(json.dumps({
            "model": alias, "provider": result["provider"], "providerModel": result["providerModel"],
            "llmRequestId": result["llmRequestId"], "finishReason": result["finishReason"],
            "usage": result.get("usage"), "ok": True,
        }))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, urllib.error.URLError, TimeoutError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
