#!/usr/bin/env python3
"""Verify real API/worker containers without exposing environment credentials."""
import argparse
import datetime
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
SERVICES = ("catalog", "orders", "inventory")


def run(arguments: list[str], *, success: bool = True, timeout: int = 30) -> subprocess.CompletedProcess:
    result = subprocess.run(arguments, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if success and result.returncode:
        raise RuntimeError("Runtime inspection command failed: " + arguments[0])
    return result


def http_status(container: str, path: str) -> int:
    result = run(["docker", "exec", container, "wget", "-S", "-O", "/dev/null",
                  "http://127.0.0.1:8080" + path], success=False)
    statuses = re.findall(r"HTTP/[0-9.]+\s+(\d{3})", result.stdout + result.stderr)
    if not statuses:
        raise RuntimeError("Container probe did not produce an HTTP response")
    return int(statuses[-1])


def inspect_service(service: str, minimum: int, worker: bool) -> list[dict]:
    identifiers = run(["docker", "compose", "ps", "-q", service]).stdout.split()
    if len(identifiers) < minimum:
        raise RuntimeError(f"{service}: expected at least {minimum} running replicas")
    records = []
    for identifier in identifiers:
        value = json.loads(run(["docker", "inspect", identifier]).stdout)[0]
        environment = dict(entry.split("=", 1) for entry in value["Config"]["Env"] if "=" in entry)
        expected = {"APP_ROLE": "worker" if worker else "api",
                    "API_ENABLED": "false" if worker else "true",
                    "WORKERS_ENABLED": "true" if worker else "false"}
        if any(environment.get(key) != required for key, required in expected.items()):
            raise RuntimeError(f"{service}: runtime role flags do not match")
        if service.startswith("inventory") and (
                not environment.get("INVENTORY_EXPIRATIONS_QUEUE_URL", "").endswith("/inventory-expirations")
                or environment.get("LOCAL_RECOVERY_ENABLED") != "false"):
            raise RuntimeError(f"{service}: dedicated expiration queue or recovery isolation is missing")
        if value["State"].get("Health", {}).get("Status") != "healthy":
            raise RuntimeError(f"{service}: Docker health is not healthy")
        if value["HostConfig"].get("Memory") != 512 * 1024 * 1024:
            raise RuntimeError(f"{service}: expected a 512 MiB memory limit")
        if worker and any(value["HostConfig"].get("PortBindings", {}).values()):
            raise RuntimeError(f"{service}: workers must not publish host ports")
        probes = {"readiness": http_status(identifier, "/actuator/health/readiness"),
                  "prometheus": http_status(identifier, "/actuator/prometheus")}
        if any(status != 200 for status in probes.values()):
            raise RuntimeError(f"{service}: management probe failed")
        if worker:
            probes.update(api=http_status(identifier, "/api/v1/events"),
                          internal=http_status(identifier, "/internal/runtime-probe"))
            if probes["api"] != 404 or probes["internal"] != 404:
                raise RuntimeError(f"{service}: a business HTTP route remained available")
        records.append({"containerId": identifier, "imageId": value["Image"],
                        "role": expected["APP_ROLE"], "health": "healthy", "probes": probes})
    return records


def expiration_recovery_contract() -> dict:
    identifiers = run(["docker", "compose", "ps", "-q", "expiry-recovery"]).stdout.split()
    if len(identifiers) != 1:
        raise RuntimeError("Exactly one local recovery publisher is required")
    publisher = json.loads(run(["docker", "inspect", identifiers[0]]).stdout)[0]
    if publisher["State"].get("Health", {}).get("Status") != "healthy":
        raise RuntimeError("Local recovery publisher has not sent a healthy tick")
    result = run(["docker", "compose", "run", "--rm", "--no-deps", "--entrypoint", "aws", "bootstrap",
                  "sqs", "get-queue-attributes", "--endpoint-url", "http://elasticmq:9324",
                  "--queue-url", "http://elasticmq:9324/000000000000/inventory-expirations",
                  "--attribute-names", "RedrivePolicy", "VisibilityTimeout", "ReceiveMessageWaitTimeSeconds"])
    attributes = json.loads(result.stdout)["Attributes"]
    redrive = json.loads(attributes["RedrivePolicy"])
    if (int(redrive["maxReceiveCount"]) != 5
            or not redrive["deadLetterTargetArn"].endswith(":inventory-expirations-dlq")
            or attributes["VisibilityTimeout"] != "60" or attributes["ReceiveMessageWaitTimeSeconds"] != "20"):
        raise RuntimeError("Dedicated expiration queue does not match the retry/DLQ contract")
    return {"publisherContainerId": identifiers[0], "publisherHealth": "healthy", "singlePublisher": True,
            "queue": "inventory-expirations", "dlq": "inventory-expirations-dlq", "maxReceiveCount": 5,
            "visibilityTimeoutSeconds": 60, "longPollSeconds": 20, "workerDirectLocalSweep": False}


def local_http(port: int, path: str, body: dict | None = None) -> tuple[int, dict]:
    headers = {"Authorization": "Bearer local-admin", "Content-Type": "application/json"}
    if body is not None:
        headers["Idempotency-Key"] = uuid.uuid4().hex
    request = urllib.request.Request("http://127.0.0.1:" + str(port) + path,
                                     data=json.dumps(body).encode() if body is not None else None, headers=headers)
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.status, json.loads(response.read())


def verify_provision_recovery(replicas: int) -> dict:
    """Prove queue-backed provisioning survives loss of every consumer replica."""
    command = ["docker", "compose"]
    run([*command, "stop", "inventory-worker"], timeout=90)
    started = time.monotonic()
    try:
        if local_http(18082, "/actuator/health/readiness")[0] != 200:
            raise RuntimeError("Inventory API did not remain ready during consumer outage")
        status, event = local_http(18080, "/api/v1/events", {
            "name": "Worker recovery " + uuid.uuid4().hex, "venue": "Local runtime verification",
            "startsAt": (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=30)).isoformat(), "capacity": 2})
        if status != 202 or event.get("status") != "PROVISIONING":
            raise RuntimeError("Creation did not durably accept asynchronous provisioning")
        event_id = event["eventId"]
        time.sleep(2)
        status, pending = local_http(18080, "/api/v1/events/" + event_id)
        if status != 200 or pending.get("status") != "PROVISIONING":
            raise RuntimeError("Event changed state despite all Inventory workers being stopped")
    finally:
        run([*command, "up", "-d", "--no-build", "--wait", "--wait-timeout", "180",
             "--scale", "inventory-worker=" + str(replicas), "inventory-worker"], timeout=240)
    # A receive already in flight when a worker dies may consume one 60s visibility
    # period before redelivery; include long-poll and outbox propagation afterward.
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        status, completed = local_http(18080, "/api/v1/events/" + event_id)
        if status == 200 and completed.get("status") == "ACTIVE":
            return {"status": "passed", "eventId": event_id, "beforeRestore": "PROVISIONING",
                    "afterRestore": "ACTIVE", "inventoryApiStayedReady": True,
                    "restoredReplicas": replicas, "elapsedSeconds": round(time.monotonic() - started, 2)}
        time.sleep(0.25)
    raise RuntimeError("Durable provisioning did not resume after worker recovery: " + event_id)


def verify(minimum_inventory_workers: int, output: Path, provision_recovery: bool = False) -> None:
    checks = {}
    for context in SERVICES:
        api = inspect_service(context, 1, False)
        minimum = minimum_inventory_workers if context == "inventory" else 1
        workers = inspect_service(context + "-worker", minimum, True)
        if len({record["imageId"] for record in api + workers}) != 1:
            raise RuntimeError(f"{context}: API and workers use different image builds")
        checks[context] = {"api": api, "workers": workers}
    recovery = None
    queue = expiration_recovery_contract()
    if provision_recovery:
        recovery = verify_provision_recovery(len(checks["inventory"]["workers"]))
        checks["inventory"]["workers"] = inspect_service("inventory-worker", minimum_inventory_workers, True)
    report = {"status": "passed", "checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "commit": run(["git", "rev-parse", "HEAD"]).stdout.strip(),
              "workingTreeDirty": bool(run(["git", "status", "--porcelain"]).stdout.strip()),
              "scope": "local-runtime-role-isolation-and-replica-readiness", "contexts": checks,
              "expirationQueue": queue}
    if recovery:
        report["provisionRecovery"] = recovery
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "passed", "contexts": len(checks),
                      "inventoryWorkers": len(checks["inventory"]["workers"]), "evidence": str(output)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--min-inventory-workers", type=int, default=2)
    parser.add_argument("--provision-recovery", action="store_true",
                        help="Stop Inventory workers, create a local event, restore replicas and verify ACTIVE")
    parser.add_argument("--output", type=Path, default=ROOT / "build/quality/runtime-roles.json")
    args = parser.parse_args()
    try:
        if args.min_inventory_workers < 1:
            raise RuntimeError("At least one inventory worker is required")
        verify(args.min_inventory_workers, args.output.resolve(), args.provision_recovery)
    except (RuntimeError, subprocess.TimeoutExpired, OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
