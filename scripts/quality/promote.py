#!/usr/bin/env python3
"""Validate a promotion against a successful immutable candidate workflow run."""
import json
import os
from pathlib import Path
import re
import sys
import tempfile

from artifacts import download, verified_run
from quality import QualityError, ROOT, branch_gate, execute, git, verify_manifest


def validate(repo_path: Path) -> None:
    if os.environ.get("GITHUB_EVENT_NAME") != "pull_request_target":
        raise QualityError("Promotion validation only runs from trusted pull_request_target")
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    pull = event["pull_request"]
    repo = event["repository"]["full_name"]
    base, head = pull["base"]["ref"], pull["head"]["ref"]
    branch_gate(base, head, pull["head"]["repo"]["full_name"] == repo)
    if base not in ("release", "main"):
        raise QualityError("This is not a permanent-branch promotion")
    match = re.search(r"(?m)^Candidate-Run:\s*([0-9]+)\s*$", pull.get("body") or "")
    if not match:
        raise QualityError("Promotion PR must identify its trusted Candidate-Run")
    run_id = int(match.group(1))
    run = verified_run(repo, run_id, "candidate.yml", event="workflow_dispatch")
    source = run["head_sha"]
    with tempfile.TemporaryDirectory(prefix="ticketing-promotion-") as temporary:
        destination = Path(temporary)
        download(repo, run_id, "candidate-" + source, destination)
        manifest = json.loads((destination / "manifest.json").read_text(encoding="utf-8"))
        if git(repo_path, "rev-parse", "HEAD") != pull["head"]["sha"]:
            raise QualityError("Promotion checkout is stale")
        # The approved source must actually be contained in the promoted history.
        execute(["git", "-C", str(repo_path), "merge-base", "--is-ancestor", source, pull["head"]["sha"]])
        tree = git(repo_path, "rev-parse", "HEAD^{tree}")
        verify_manifest(manifest, tree, source)
        if base == "release" and source != pull["head"]["sha"]:
            raise QualityError("Develop changed after the candidate was approved")
        execute(["git", "-C", str(repo_path), "-c", "user.name=CI", "-c", "user.email=ci@example.invalid",
                 "merge", "--no-ff", "--no-commit", pull["base"]["sha"]])
        if git(repo_path, "write-tree") != tree:
            raise QualityError("Promotion merge would change the approved candidate content")
        print(json.dumps({"status": "passed", "sourceCommit": source, "target": base,
                          "candidateRun": run_id, "tree": tree}))


if __name__ == "__main__":
    try:
        validate(Path(sys.argv[1]).resolve())
    except (QualityError, ValueError, KeyError, OSError) as error:
        print(f"Promotion blocked: {error}", file=sys.stderr)
        raise SystemExit(1)
