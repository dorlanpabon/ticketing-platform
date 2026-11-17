#!/usr/bin/env python3
"""Read event JSON as data; never interpolate contributor text into shell code."""
import json
import os
from pathlib import Path
import re

from quality import SHA, QualityError, branch_gate


def context(event: dict, name: str, frozen: bool = False, *, ref: str = "", sha: str = "") -> dict:
    if name == "pull_request_target":
        pull = event["pull_request"]
        same = pull["head"]["repo"]["full_name"] == event["repository"]["full_name"]
        branch_gate(pull["base"]["ref"], pull["head"]["ref"], same, frozen)
        return {"head": pull["head"]["sha"], "base": pull["base"]["sha"], "before": "",
                "pull": str(int(event["number"])), "target": pull["base"]["ref"], "source": pull["head"]["ref"]}
    if name == "push":
        if event.get("deleted"):
            raise QualityError("Deleted refs have no candidate to validate")
        source = event["ref"].removeprefix("refs/heads/")
        if not re.fullmatch(r"(?:main|release|develop|(?:feature|fix|chore)/[a-z0-9][a-z0-9._/-]*)", source):
            raise QualityError("Unsupported push branch")
        return {"head": event["after"], "before": event.get("before", ""), "base": "", "pull": "",
                "target": source, "source": source}
    if name == "workflow_dispatch":
        if ref != "refs/heads/main" or not SHA.fullmatch(sha):
            raise QualityError("Manual quality validation requires main and a full commit SHA")
        return {"head": sha, "before": sha, "base": "", "pull": "", "target": "main", "source": "main"}
    raise QualityError("Unsupported workflow event")


if __name__ == "__main__":
    values = context(json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8")),
                     os.environ["GITHUB_EVENT_NAME"], os.environ.get("RELEASE_FROZEN") == "true",
                     ref=os.environ.get("GITHUB_REF", ""), sha=os.environ.get("GITHUB_SHA", ""))
    for name in ("head", "base", "before"):
        if values[name] and not re.fullmatch(r"[0-9a-f]{40}", values[name]):
            raise SystemExit("Invalid Git identifier in event")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        for name, value in values.items():
            if "\n" in value or "\r" in value:
                raise SystemExit("Invalid multiline context value")
            output.write(f"{name}={value}\n")
