#!/usr/bin/env python3
"""Produce an immutable candidate only from exact, successful DEV evidence.

This command is for an explicitly dispatched trusted develop workflow. It never
deploys AWS resources. Missing acceptance tests block candidate publication.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

from artifacts import api, download, verified_run
from quality import QualityError, ROOT, SERVICES, SHA, check_coverage, content_hash, execute, git, tool, verify_manifest, write_json


def acceptance_evidence(path: Path, root: Path, sha: str, tree: str) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("commit") != sha or value.get("tree") != tree:
        raise QualityError("Acceptance report belongs to a different candidate")
    if value.get("dirty") is not False:
        raise QualityError("Acceptance evidence requires a clean candidate tree")
    result = {}
    for name in ("integration", "architecture", "concurrency", "recovery", "containerSmoke"):
        check = value.get("checks", {}).get(name, {})
        report = (root / check.get("evidence", "")).resolve()
        if check.get("status") != "passed" or not report.is_relative_to(root) or not report.is_file() or not report.stat().st_size:
            raise QualityError(f"Missing actual acceptance evidence: {name}")
        result[name] = {"path": report.relative_to(root).as_posix(), "sha256": hashlib.sha256(report.read_bytes()).hexdigest()}
    return result


def build_candidate(run_id: int | None, infra_sha: str | None) -> None:
    if os.environ.get("GITHUB_REF") != "refs/heads/develop" or os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch":
        raise QualityError("Candidate publication requires an explicit develop dispatch")
    if os.environ.get("RELEASE_FROZEN") != "true":
        raise QualityError("Freeze functional develop merges before cutting a candidate")
    sha = os.environ.get("GITHUB_SHA", "")
    if not SHA.fullmatch(sha) or git(ROOT, "rev-parse", "HEAD") != sha:
        raise QualityError("Candidate checkout does not match the workflow SHA")
    if infra_sha and not SHA.fullmatch(infra_sha):
        raise QualityError("Infrastructure identity must be a full external Git SHA")
    repo = os.environ["GITHUB_REPOSITORY"]
    if run_id is None:
        runs = api(f"repos/{repo}/actions/workflows/quality.yml/runs?branch=develop&event=push&status=success&per_page=100")["workflow_runs"]
        matching = [item for item in runs if item.get("head_sha") == sha and item.get("head_branch") == "develop" and item.get("event") == "push"]
        if not matching:
            raise QualityError("No successful exact-SHA develop Quality run exists")
        run_id = int(matching[0]["id"])
    verified_run(repo, run_id, "quality.yml", sha, "push")
    tree = git(ROOT, "rev-parse", "HEAD^{tree}")
    output = ROOT / "build/quality/candidate"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ticketing-prerequisites-") as temporary:
        prerequisites = Path(temporary)
        download(repo, run_id, "sonar-" + sha, prerequisites / "sonar")
        download(repo, run_id, "unit-" + sha, prerequisites / "unit")
        download(repo, run_id, f"static-{sha}-{run_id}", prerequisites / "static")
        summary = json.loads((prerequisites / "unit/summary.json").read_text(encoding="utf-8"))
        if summary.get("commit") != sha or summary.get("tree") != tree or summary.get("status") != "passed":
            raise QualityError("Unit evidence does not match the candidate")
        sonar = {}
        for service in SERVICES:
            if summary.get("services", {}).get(service, {}).get("status") != "passed":
                raise QualityError(f"Missing unit build for {service}")
            check_coverage(prerequisites / "unit" / service / "jacoco.xml")
            sonar[service] = json.loads((prerequisites / "sonar" / f"{service}.json").read_text(encoding="utf-8"))
        static = json.loads((prerequisites / "static/commits.json").read_text(encoding="utf-8"))
        if static.get("head") != sha or static.get("status") != "passed":
            raise QualityError("Static evidence does not match the candidate")
        head = next((entry for entry in static.get("commits", []) if entry.get("commit") == sha), None)
        if not head or head.get("tree") != tree or head.get("status") != "passed":
            raise QualityError("Exact candidate static evidence is absent")
        if any(head.get("checks", {}).get(name) != "passed" for name in ("gitleaks", "checkov", "trivy")):
            raise QualityError("Candidate security/configuration scans did not pass")
        policy_hash = head.get("policyHash")
        acceptance = ROOT / "scripts/acceptance/verify.py"
        if not acceptance.is_file():
            raise QualityError("Acceptance runner is not implemented; candidate publication stays blocked")
        environment = os.environ.copy()
        for key in ("GH_TOKEN", "GITHUB_TOKEN", "SONAR_TOKEN", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN"):
            environment.pop(key, None)
        compose = ["docker", "compose", "-f", str(ROOT / "compose.yaml")]
        services = ["catalog", "orders", "inventory", "catalog-worker", "orders-worker", "inventory-worker"]
        try:
            execute([*compose, "up", "-d", "--build", "--wait", "--wait-timeout", "240",
                     "--scale", "inventory-worker=2", *services], cwd=ROOT, env=environment, timeout=1800)
            execute([sys.executable, str(acceptance)], cwd=ROOT, env=environment, timeout=1800)
        finally:
            execute([*compose, "stop", *services], cwd=ROOT, env=environment, timeout=180)
        proofs = acceptance_evidence(ROOT / "build/quality/acceptance.json", ROOT, sha, tree)
        # Build the exact application sources again only to materialize their jar
        # for image construction. QA/PROD never rebuild these published images.
        for service in SERVICES:
            folder = ROOT / "services" / service
            wrapper = str(folder / ("gradlew.bat" if os.name == "nt" else "gradlew"))
            execute([wrapper, "--no-daemon", "--no-configuration-cache", "build", "jacocoMergedReport", "unitCoverageGate"], cwd=folder, env=environment, timeout=1800)
        # Detect build/acceptance scripts changing any tracked source.
        if git(ROOT, "status", "--porcelain", "--untracked-files=no"):
            raise QualityError("Build changed tracked candidate files")
        token = os.environ.get("GH_TOKEN", "")
        actor = os.environ.get("GITHUB_ACTOR", "")
        if not token or not actor:
            raise QualityError("Scoped registry publishing credentials are required")
        login = subprocess.run(["docker", "login", "ghcr.io", "--username", actor, "--password-stdin"],
                               input=token.encode(), capture_output=True, timeout=60, check=False)
        if login.returncode:
            raise QualityError("Registry login failed")
        versions = json.loads((ROOT / "config/quality/tools.json").read_text(encoding="utf-8"))["tools"]
        trivy = tool("trivy", versions["trivy"])
        images = {}
        for service in SERVICES:
            service_root = ROOT / "services" / service
            jars = [item for item in (service_root / "applications/app-service/build/libs").glob("*.jar") if not item.name.endswith("-plain.jar")]
            if len(jars) != 1:
                raise QualityError(f"Expected one executable jar for {service}")
            tag = f"ghcr.io/{repo.lower()}/{service}:{sha}"
            with tempfile.TemporaryDirectory(prefix="ticketing-image-") as context:
                context_path = Path(context)
                shutil.copy2(jars[0], context_path / jars[0].name)
                shutil.copy2(service_root / "deployment/Dockerfile", context_path / "Dockerfile")
                execute(["docker", "build", "--label", "org.opencontainers.image.revision=" + sha,
                         "--label", "org.opencontainers.image.source=https://github.com/" + repo,
                         "--tag", tag, str(context_path)], timeout=900)
            execute([trivy, "image", "--severity", "HIGH,CRITICAL", "--exit-code", "1", "--scanners", "vuln",
                     "--format", "json", "--output", str(output / f"{service}-image-security.json"), tag], timeout=900)
            execute(["docker", "push", tag], timeout=900)
            digests = json.loads(execute(["docker", "image", "inspect", tag, "--format", "{{json .RepoDigests}}"] ).stdout)
            matching = [item for item in digests if item.startswith(tag.rsplit(":", 1)[0] + "@sha256:")]
            if len(matching) != 1:
                raise QualityError("Registry did not establish a unique immutable image digest")
            images[service] = matching[0]
            execute([trivy, "image", "--format", "cyclonedx", "--output", str(output / f"{service}-sbom.json"), matching[0]], timeout=900)
        checks = {name: "passed" for name in ("unit", "integration", "architecture", "concurrency", "recovery", "containerSmoke", "static", "iac", "security", "sbom")}
        manifest = {"schemaVersion": 1, "sourceCommit": sha, "tree": tree, "images": images, "sonar": sonar,
                    "checks": checks, "policyHash": policy_hash, "qualityRunId": run_id,
                    "acceptanceEvidence": proofs, "configurationScope": "Application Dockerfiles and workflows; Terraform is owned by ticketing-infra",
                    "infraGitSha": infra_sha, "validationEnvironment": "local containers; not an AWS deployment"}
        verify_manifest(manifest, tree, sha)
        write_json(output / "manifest.json", manifest)
        print(json.dumps({"candidate": sha, "images": images, "awsDeployed": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quality-run", type=int)
    parser.add_argument("--infra-sha")
    args = parser.parse_args()
    try:
        build_candidate(args.quality_run, args.infra_sha or None)
    except (QualityError, OSError, ValueError, KeyError) as error:
        print(f"Candidate blocked: {error}", file=sys.stderr)
        raise SystemExit(1)
