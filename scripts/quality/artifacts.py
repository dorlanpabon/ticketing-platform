"""Read trusted GitHub workflow artifacts without executing their contents."""
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import zipfile

from quality import QualityError, execute


def api(endpoint: str) -> dict:
    return json.loads(execute(["gh", "api", endpoint], timeout=120).stdout)


def verified_run(repo: str, run_id: int, workflow: str, sha: str | None = None,
                 event: str | None = None) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise QualityError("Invalid repository identity")
    expected = api(f"repos/{repo}/actions/workflows/{workflow}")["id"]
    run = api(f"repos/{repo}/actions/runs/{int(run_id)}")
    if run.get("workflow_id") != expected or run.get("conclusion") != "success" or run.get("status") != "completed":
        raise QualityError("Evidence did not come from the successful trusted workflow")
    if run.get("head_repository", {}).get("full_name") != repo or run.get("head_branch") != "develop":
        raise QualityError("Evidence must belong to this repository's develop branch")
    if sha and run.get("head_sha") != sha:
        raise QualityError("Workflow evidence belongs to a different SHA")
    if event and run.get("event") != event:
        raise QualityError("Evidence came from an unexpected workflow event")
    return run


def download(repo: str, run_id: int, name: str, destination: Path) -> None:
    listing = api(f"repos/{repo}/actions/runs/{int(run_id)}/artifacts?per_page=100")
    artifacts = [item for item in listing["artifacts"] if item["name"] == name and not item["expired"]]
    if len(artifacts) != 1:
        raise QualityError(f"Expected one unexpired evidence artifact: {name}")
    blob = execute(["gh", "api", f"repos/{repo}/actions/artifacts/{int(artifacts[0]['id'])}/zip"], timeout=120).stdout
    with zipfile.ZipFile(io.BytesIO(blob)) as source:
        if sum(entry.file_size for entry in source.infolist()) > 128 * 1024 * 1024:
            raise QualityError("Evidence artifact exceeds the safe extraction limit")
        for entry in source.infolist():
            relative = PurePosixPath(entry.filename)
            if relative.is_absolute() or ".." in relative.parts or "\\" in entry.filename:
                raise QualityError("Unsafe evidence archive path")
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise QualityError("Evidence symlinks are not accepted")
            target = destination.joinpath(*relative.parts)
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read(entry))
