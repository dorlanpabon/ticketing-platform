import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "ci_event.py"
MAIN_SHA = "a" * 40


class ManualDispatchTest(unittest.TestCase):
    def dispatch(self, ref, sha):
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / "event.json"
            output = Path(directory) / "github-output"
            event.write_text(json.dumps({"ref": "release", "inputs": {"sha": "b" * 40}}), encoding="utf-8")
            environment = os.environ | {
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_EVENT_PATH": str(event),
                "GITHUB_OUTPUT": str(output),
                "GITHUB_REF": ref,
                "GITHUB_SHA": sha,
            }
            result = subprocess.run([sys.executable, str(SCRIPT)], check=False, capture_output=True, text=True,
                                    env=environment)
            lines = output.read_text(encoding="utf-8").splitlines() if output.is_file() else []
            values = dict(line.split("=", 1) for line in lines)
            return result, values

    def test_dispatch_uses_main_sha_from_github_context_for_both_range_limits(self):
        result, values = self.dispatch("refs/heads/main", MAIN_SHA)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"head": MAIN_SHA, "before": MAIN_SHA, "base": "", "pull": "",
                          "target": "main", "source": "main"}, values)

    def test_dispatch_rejects_other_refs_and_invalid_shas(self):
        for ref, sha in (("refs/heads/release", MAIN_SHA), ("refs/tags/main", MAIN_SHA),
                         ("refs/heads/main", ""), ("refs/heads/main", "short")):
            with self.subTest(ref=ref, sha=sha):
                result, values = self.dispatch(ref, sha)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Manual quality validation requires main and a full commit SHA", result.stderr)
                self.assertEqual({}, values)
