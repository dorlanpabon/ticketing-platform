#!/usr/bin/env python3
"""Idempotently configure the local Community projects and strict Quality Gate.

Provide a local administrator token through SONAR_ADMIN_TOKEN. Project analysis
tokens are provisioned separately; this script never prints or creates tokens.
"""
import os
import sys
import urllib.parse

from quality import QualityError, SERVICES, sonar_request

GATE = "Ticketing 90 percent"
CONDITIONS = {
    "coverage": ("LT", "90"), "new_coverage": ("LT", "90"),
    "duplicated_lines_density": ("GT", "3"), "new_duplicated_lines_density": ("GT", "3"),
    "reliability_rating": ("GT", "1"), "security_rating": ("GT", "1"), "sqale_rating": ("GT", "1"),
    "bugs": ("GT", "0"), "vulnerabilities": ("GT", "0"),
    "security_hotspots_reviewed": ("LT", "100"), "new_security_hotspots_reviewed": ("LT", "100"),
}


def configure() -> None:
    token = os.environ.get("SONAR_ADMIN_TOKEN", "")
    if not token:
        raise QualityError("SONAR_ADMIN_TOKEN must be supplied locally")
    host = os.environ.get("SONAR_HOST_URL") or "http://localhost:19000"
    gates = sonar_request(host, "/api/qualitygates/list", token).get("qualitygates", [])
    if not any(gate.get("name") == GATE for gate in gates):
        sonar_request(host, "/api/qualitygates/create", token, data={"name": GATE})
    detail = sonar_request(host, "/api/qualitygates/show?" + urllib.parse.urlencode({"name": GATE}), token)
    existing = {item["metric"]: item for item in detail.get("conditions", [])}
    for metric, (operator, threshold) in CONDITIONS.items():
        current = existing.get(metric)
        if current and current.get("op") == operator and current.get("error") == threshold:
            continue
        if current:
            sonar_request(host, "/api/qualitygates/update_condition", token,
                          data={"id": current["id"], "metric": metric, "op": operator, "error": threshold})
        else:
            sonar_request(host, "/api/qualitygates/create_condition", token,
                          data={"gateName": GATE, "metric": metric, "op": operator, "error": threshold})
    for service in SERVICES:
        key = "ticketing-" + service
        found = sonar_request(host, "/api/projects/search?" + urllib.parse.urlencode({"projects": key}), token)
        if not found.get("components"):
            sonar_request(host, "/api/projects/create", token, data={"project": key, "name": key, "visibility": "private"})
        sonar_request(host, "/api/qualitygates/select", token, data={"projectKey": key, "gateName": GATE})
        # Do not waive coverage/duplication for changes below twenty new lines.
        sonar_request(host, "/api/settings/set", token,
                      data={"component": key, "key": "sonar.qualitygate.ignoreSmallChanges", "value": "false"})
    print("Configured three Community projects and the strict Quality Gate; no analysis has run.")


if __name__ == "__main__":
    try:
        configure()
    except QualityError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
