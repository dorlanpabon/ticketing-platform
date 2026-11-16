import copy
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("quality", Path(__file__).resolve().parents[1] / "quality.py")
quality = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality)


class GitSnapshotsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.git("init", "-b", "main")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Quality Test")
        self.base = self.commit("base.txt", "base")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.repo), *args], check=True,
                              capture_output=True).stdout.decode().strip()

    def commit(self, name, content):
        (self.repo / name).write_text(content, encoding="utf-8")
        self.git("add", name)
        self.git("commit", "-m", "test: fixture")
        return self.git("rev-parse", "HEAD")

    def test_multiple_commits_include_merge(self):
        self.git("switch", "-c", "feature/one")
        first = self.commit("first.txt", "1")
        second = self.commit("second.txt", "2")
        self.git("switch", "main")
        self.git("merge", "--no-ff", "feature/one", "-m", "test: merge")
        merge = self.git("rev-parse", "HEAD")
        self.assertEqual([first, second, merge], quality.introduced_commits(self.repo, self.base, merge))

    def test_new_branch_does_not_assume_prior_history_was_scanned(self):
        head = self.commit("next.txt", "next")
        self.assertEqual([self.base, head], quality.introduced_commits(self.repo, "0" * 40, head))

    def test_divergent_push_includes_new_side(self):
        old = self.commit("old.txt", "old")
        self.git("switch", "-c", "feature/new", self.base)
        new = self.commit("new.txt", "new")
        self.assertEqual([new], quality.introduced_commits(self.repo, old, new))

    def test_index_snapshot_does_not_include_unstaged_changes(self):
        (self.repo / "base.txt").write_text("staged", encoding="utf-8")
        self.git("add", "base.txt")
        (self.repo / "base.txt").write_text("unstaged", encoding="utf-8")
        tree = self.git("write-tree")
        target = self.repo / "snapshot"
        quality.snapshot(self.repo, tree, target)
        self.assertEqual("staged", (target / "base.txt").read_text())
        self.assertEqual("unstaged", (self.repo / "base.txt").read_text())

    def test_export_ignore_cannot_hide_staged_files(self):
        self.commit(".gitattributes", "base.txt export-ignore\n")
        target = self.repo / "snapshot"
        quality.snapshot(self.repo, "HEAD", target)
        self.assertEqual("base", (target / "base.txt").read_text())

    def test_snapshot_preserves_crlf_and_binary_git_blobs(self):
        self.git("config", "core.autocrlf", "false")
        files = {"policy.txt": b"strict\r\n", "policy.bin": b"\xff\x00\r\n"}
        for name, content in files.items():
            (self.repo / name).write_bytes(content)
            self.git("add", name)
        target = self.repo / "snapshot"
        quality.snapshot(self.repo, self.git("write-tree"), target)
        for name, content in files.items():
            self.assertEqual(content, (target / name).read_bytes())


class ValidationTest(unittest.TestCase):
    def test_reject_archive_escape_and_symlink(self):
        for name, kind in (("../escape", tarfile.REGTYPE), ("link", tarfile.SYMTYPE)):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                blob = io.BytesIO()
                with tarfile.open(fileobj=blob, mode="w") as archive:
                    member = tarfile.TarInfo(name)
                    member.type = kind
                    archive.addfile(member)
                with self.assertRaises(quality.QualityError):
                    quality.safe_extract(blob.getvalue(), Path(directory))

    def test_policy_change_needs_exact_external_approval(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trusted, candidate = root / "trusted", root / "candidate"
            for location in (trusted, candidate):
                (location / "config/quality").mkdir(parents=True)
            (trusted / "config/quality/rule").write_text("strict")
            (candidate / "config/quality/rule").write_text("disabled")
            with self.assertRaises(quality.QualityError):
                quality.policy_guard(trusted, candidate)
            quality.policy_guard(trusted, candidate, quality.content_hash(candidate, True))

    def test_policy_fingerprint_normalizes_only_utf8_text_line_endings(self):
        with tempfile.TemporaryDirectory() as directory:
            trusted, candidate = Path(directory) / "trusted", Path(directory) / "candidate"
            for location in (trusted, candidate):
                (location / "config/quality").mkdir(parents=True)
            text = "strict=si\nlabel=política\n".encode("utf-8")
            (trusted / "config/quality/rule").write_bytes(text)
            rule = candidate / "config/quality/rule"
            rule.write_bytes(text.replace(b"\n", b"\r\n"))
            self.assertEqual(quality.content_hash(trusted, True), quality.content_hash(candidate, True))
            quality.policy_guard(trusted, candidate)
            self.assertNotEqual(quality.content_hash(trusted), quality.content_hash(candidate))
            self.assertEqual(text.replace(b"\n", b"\r\n"), rule.read_bytes())
            rule.write_bytes(b"strict=no\r\n")
            with self.assertRaises(quality.QualityError):
                quality.policy_guard(trusted, candidate)

    def test_policy_fingerprint_keeps_binary_and_non_utf8_bytes_exact(self):
        for content in (b"binary\x00\r\n", b"\xff\r\n"):
            with self.subTest(content=content), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "config/quality").mkdir(parents=True)
                rule = root / "config/quality/rule"
                rule.write_bytes(content)
                expected = quality.content_hash(root, True)
                rule.write_bytes(content.replace(b"\r\n", b"\n"))
                self.assertNotEqual(expected, quality.content_hash(root, True))

    def test_valid_branch_flow_and_release_freeze(self):
        for base, head in (("develop", "feature/reservations"), ("release", "develop"), ("main", "release")):
            quality.branch_gate(base, head, True)
        for base, head, same, frozen in (("main", "feature/bypass", True, False),
                                         ("release", "fix/bypass", True, False),
                                         ("develop", "feature/fork", False, False),
                                         ("develop", "feature/new", True, True)):
            with self.assertRaises(quality.QualityError):
                quality.branch_gate(base, head, same, frozen)
        quality.branch_gate("develop", "fix/expiry", True, True)

    def test_unit_coverage_needs_both_counters_and_threshold(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "jacoco.xml"
            report.write_text('<report><counter type="LINE" covered="90" missed="10"/><counter type="BRANCH" covered="9" missed="1"/></report>')
            self.assertEqual(.9, quality.check_coverage(report)["LINE"]["ratio"])
            report.write_text('<report><counter type="LINE" covered="100" missed="0"/></report>')
            with self.assertRaises(quality.QualityError):
                quality.check_coverage(report)
            report.write_text('<report><counter type="LINE" covered="90" missed="10"/><counter type="BRANCH" covered="8" missed="2"/></report>')
            with self.assertRaises(quality.QualityError):
                quality.check_coverage(report)

    def test_secret_redaction(self):
        with patch.dict(os.environ, {"SONAR_TOKEN": "test-secret-value"}):
            self.assertNotIn("test-secret-value", quality.redact("token=test-secret-value"))

    def test_candidate_cannot_suppress_its_own_scanner(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Example.java"
            source.write_text('class Example { int unsafe; } // NOPMD\n')
            with self.assertRaises(quality.QualityError):
                quality.reject_candidate_suppressions(root, [source])
            source.unlink()
            ignore = root / ".trivyignore"
            ignore.write_text("CVE-2099-0001")
            with self.assertRaises(quality.QualityError):
                quality.reject_candidate_suppressions(root, [ignore])

    def test_build_outputs_do_not_change_policy_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradle").mkdir()
            (root / "gradle/quality.gradle").write_text("strict")
            expected = quality.content_hash(root, True)
            (root / "build/cache").mkdir(parents=True)
            (root / "build/cache/build.gradle").write_text("generated")
            self.assertEqual(expected, quality.content_hash(root, True))

    def test_sonar_cannot_send_token_to_untrusted_http_host(self):
        with self.assertRaises(quality.QualityError):
            quality.sonar_request("http://untrusted.invalid:9000", "/api/server/version", "secret")

    @staticmethod
    def metrics():
        return {"coverage": "95", "new_coverage": "95", "duplicated_lines_density": "1",
                "reliability_rating": "1", "security_rating": "1", "sqale_rating": "1",
                "bugs": "0", "vulnerabilities": "0", "ncloc": "100", "lines_to_cover": "90"}

    @classmethod
    def manifest(cls):
        sha = "a" * 40
        return {"schemaVersion": 1, "sourceCommit": sha, "tree": "b" * 40, "policyHash": "c" * 64,
                "images": {s: f"registry.invalid/{s}@sha256:" + "d" * 64 for s in quality.SERVICES},
                "sonar": {s: {"project": "ticketing-" + s, "commit": sha, "qualityGate": "OK",
                               "analysisId": s, "ceTaskId": s, "metrics": cls.metrics()} for s in quality.SERVICES},
                "checks": {name: "passed" for name in ("unit", "integration", "architecture", "concurrency", "recovery", "containerSmoke", "expiry", "static", "configuration", "security", "sbom")}}

    def test_manifest_accepts_same_tree_different_merge_sha(self):
        quality.verify_manifest(self.manifest(), "b" * 40)

    def test_manifest_rejects_changed_content_failed_service_missing_digest_and_old_sha(self):
        changes = [lambda m: m.update(tree="x" * 40),
                   lambda m: m["sonar"]["orders"].update(qualityGate="ERROR"),
                   lambda m: m["images"].update(catalog="registry/catalog:latest"),
                   lambda m: m["sonar"]["catalog"].update(commit="e" * 40),
                   lambda m: m["checks"].update(recovery="not_run")]
        for change in changes:
            manifest = copy.deepcopy(self.manifest())
            change(manifest)
            with self.assertRaises(quality.QualityError):
                quality.verify_manifest(manifest, "b" * 40)

    def test_cloud_promotion_requires_external_infra_commit(self):
        manifest = self.manifest()
        with self.assertRaises(quality.QualityError):
            quality.verify_manifest(manifest, "b" * 40, cloud=True)
        manifest["infraGitSha"] = "f" * 40
        quality.verify_manifest(manifest, "b" * 40, cloud=True)

    def test_sonar_missing_metrics_or_unreviewed_hotspots_fail(self):
        missing = self.metrics()
        del missing["lines_to_cover"]
        with self.assertRaises(quality.QualityError):
            quality.validate_sonar_metrics(missing)
        hotspots = self.metrics() | {"security_hotspots": "1", "security_hotspots_reviewed": "0"}
        with self.assertRaises(quality.QualityError):
            quality.validate_sonar_metrics(hotspots)

    def test_sonar_non_finite_and_malformed_metrics_fail_closed(self):
        for name in (*self.metrics(), "security_hotspots", "security_hotspots_reviewed"):
            for invalid in ("NaN", "Infinity", "-Infinity", "invalid", "", None):
                with self.subTest(metric=name, invalid=invalid), self.assertRaises(quality.QualityError):
                    quality.validate_sonar_metrics(self.metrics() | {name: invalid})

    def test_scoped_analysis_token_reads_only_compute_task(self):
        sha = "a" * 40
        analyses = {"analyses": [{"key": "analysis", "revision": sha}]}
        responses = [{"task": {"componentKey": "ticketing-orders", "status": "SUCCESS", "analysisId": "analysis"}},
                     analyses, {"projectStatus": {"status": "OK"}},
                     {"component": {"measures": [{"metric": key, "value": value} for key, value in self.metrics().items()]}},
                     analyses]
        with patch.object(quality, "sonar_request", side_effect=responses) as remote:
            evidence = quality.sonar_evidence("http://localhost:19000", "reader-fixture", "ticketing-orders", "task", sha,
                                              ce_token="scoped-analysis-fixture")
        self.assertEqual("OK", evidence["qualityGate"])
        self.assertEqual("scoped-analysis-fixture", remote.call_args_list[0].args[2])
        self.assertTrue(all(call.args[2] == "reader-fixture" for call in remote.call_args_list[1:]))

    def test_sonar_analysis_with_old_sha_fails(self):
        responses = [{"task": {"componentKey": "ticketing-orders", "status": "SUCCESS", "analysisId": "analysis"}},
                     {"analyses": [{"key": "analysis", "revision": "b" * 40}]}]
        with patch.object(quality, "sonar_request", side_effect=responses), self.assertRaises(quality.QualityError):
            quality.sonar_evidence("http://localhost:9000", "secret", "ticketing-orders", "task", "a" * 40)

    def test_sonar_failed_compute_task_blocks_promotion(self):
        with patch.object(quality, "sonar_request", return_value={"task": {"componentKey": "ticketing-orders", "status": "FAILED"}}), self.assertRaises(quality.QualityError):
            quality.sonar_evidence("http://localhost:9000", "secret", "ticketing-orders", "task", "a" * 40)


if __name__ == "__main__":
    unittest.main()
