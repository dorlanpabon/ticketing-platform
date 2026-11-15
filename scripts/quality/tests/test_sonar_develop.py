"""Local/CI Sonar boundaries; all Gradle and Sonar calls are mocked."""
from contextlib import redirect_stdout
import io
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sonar_develop as sonar


class SonarDevelopTest(unittest.TestCase):
    def setUp(self):
        base = sonar.ROOT / '.tools'
        base.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix='test-sonar-', dir=base)
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name) / 'repo'
        self.repo.mkdir()
        self.git('init', '-b', 'develop')
        self.git('config', 'user.name', 'Sonar Test Fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        (self.repo / '.gitignore').write_text('.tools/\nbuild/\n**/build/\n', encoding='utf-8')
        (self.repo / 'source.txt').write_text('committed source', encoding='utf-8')
        self.git('add', '.')
        self.git('commit', '-m', 'test: immutable source fixture')
        self.sha = self.git('rev-parse', 'HEAD')
        self.environment = sonar.build_environment() | {'SONAR_READ_TOKEN': 'reader-fixture', 'SONAR_TOKEN_CATALOG': 'analysis-fixture'}
        self.root_patch = patch.object(sonar, 'ROOT', self.repo)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], stderr=subprocess.STDOUT, text=True).strip()

    def identity(self, local=True, environment=None):
        with patch.dict(os.environ, self.environment if environment is None else environment, clear=True):
            return sonar.execution_identity(local)

    def ci_environment(self):
        return self.environment | {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/develop',
                                   'GITHUB_EVENT_NAME': 'push', 'GITHUB_SHA': self.sha}

    def test_explicit_local_uses_real_clean_develop_sha_without_ci_markers(self):
        self.assertEqual((self.sha, 'local'), self.identity())
        self.assertNotIn('GITHUB_REF', self.environment)

    def test_other_branch_and_detached_head_cannot_use_local_mode(self):
        self.git('switch', '-c', 'feature/other')
        with self.assertRaises(sonar.QualityError):
            self.identity()
        self.git('checkout', '--detach', self.sha)
        with self.assertRaises(sonar.QualityError):
            self.identity()

    def test_untracked_staged_and_unstaged_sources_block_analysis(self):
        (self.repo / 'untracked.txt').write_text('new source', encoding='utf-8')
        with self.assertRaises(sonar.QualityError):
            self.identity()
        self.git('add', 'untracked.txt')
        with self.assertRaises(sonar.QualityError):
            self.identity()
        self.git('commit', '-m', 'test: tracked fixture')
        (self.repo / 'source.txt').write_text('changed source', encoding='utf-8')
        with self.assertRaises(sonar.QualityError):
            self.identity()

    def test_local_mode_rejects_ci_and_pr_markers(self):
        for key, value in (('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REF', 'refs/heads/develop'),
                           ('GITHUB_SHA', self.sha), ('GITHUB_ACTIONS', 'true'), ('GITHUB_HEAD_REF', 'feature/pr'), ('CI', 'true')):
            with self.subTest(key=key), self.assertRaises(sonar.QualityError):
                self.identity(environment=self.environment | {key: value})

    def test_ci_accepts_exact_develop_push_in_detached_checkout(self):
        self.git('checkout', '--detach', self.sha)
        self.assertEqual((self.sha, 'ci'), self.identity(False, self.ci_environment()))

    def test_ci_remains_fail_closed_for_pr_other_branch_and_wrong_sha(self):
        for key, value in (('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_EVENT_NAME', 'workflow_dispatch'),
                           ('GITHUB_REF', 'refs/heads/main'), ('GITHUB_SHA', 'a' * 40), ('GITHUB_SHA', 'short'),
                           ('GITHUB_ACTIONS', 'false'), ('GITHUB_HEAD_REF', 'feature/pr'), ('GITHUB_BASE_REF', 'develop')):
            with self.subTest(key=key, value=value), self.assertRaises(sonar.QualityError):
                self.identity(False, self.ci_environment() | {key: value})

    def test_build_environment_is_an_allowlist_without_credentials(self):
        environment = self.environment | {'GITHUB_TOKEN': 'github-fixture', 'GH_TOKEN': 'gh-fixture',
                                         'AWS_SECRET_ACCESS_KEY': 'aws-fixture', 'UNRELATED_SECRET': 'other-fixture',
                                         'SONAR_JDBC_PASSWORD': 'database-fixture', 'JAVA_HOME': 'fixture-java'}
        with patch.dict(os.environ, environment, clear=True):
            actual = sonar.build_environment()
        self.assertEqual('fixture-java', actual['JAVA_HOME'])
        self.assertTrue(set(actual).isdisjoint({'SONAR_READ_TOKEN', 'SONAR_TOKEN_CATALOG', 'GITHUB_TOKEN', 'GH_TOKEN',
                                              'AWS_SECRET_ACCESS_KEY', 'UNRELATED_SECRET', 'SONAR_JDBC_PASSWORD'}))

    def test_worktree_keeps_exact_source_when_the_original_branch_changes(self):
        with sonar.analysis_checkout(self.sha, True) as checkout:
            self.git('switch', '-c', 'feature/race')
            (self.repo / 'source.txt').write_text('other branch source', encoding='utf-8')
            self.git('add', 'source.txt')
            self.git('commit', '-m', 'test: concurrent branch change')
            self.assertEqual('committed source', (checkout / 'source.txt').read_text())
            with self.assertRaises(sonar.QualityError):
                sonar.verify_checkout(self.sha, True, checkout)
        self.assertFalse(checkout.exists())
        self.assertEqual(1, self.git('worktree', 'list', '--porcelain').count('worktree '))

    def run_analysis_fixture(self, on_build=None, on_evidence=None, local=True, scan_failure=False):
        real_execute = sonar.execute
        calls = []

        def fake_execute(arguments, **kwargs):
            if arguments[0] == 'git':
                return real_execute(arguments, **kwargs)
            calls.append((arguments, kwargs['env']))
            directory = kwargs['cwd']
            if 'build' in arguments:
                for relative in ('build/reports/jacoco/merged/jacoco.xml',
                                 'domain/model/build/test-results/test/TEST-Example.xml',
                                 'domain/model/build/reports/pitest/mutations.xml',
                                 'domain/usecase/build/reports/pitest/mutations.xml'):
                    report = directory / relative
                    report.parent.mkdir(parents=True, exist_ok=True)
                    report.write_bytes(b'<?xml version="1.0"?><fixture/>\r\n')
                if on_build:
                    on_build(directory)
            if 'sonar' in arguments:
                task = directory / 'build/sonar/report-task.txt'
                task.parent.mkdir(parents=True, exist_ok=True)
                task.write_text('ceTaskId=fixture-task\nserverUrl=http://user:must-not-retain@localhost\ntoken=must-not-retain\n', encoding='utf-8')
                if scan_failure:
                    raise sonar.QualityError('Fixture scanner gate failed')
            return subprocess.CompletedProcess(arguments, 0, stdout=b'', stderr=b'')

        def evidence(*args):
            if on_evidence:
                on_evidence()
            return {'project': 'ticketing-catalog', 'commit': self.sha, 'ceTaskId': 'fixture-task', 'status': 'passed'}

        def remote_metadata(host, route, token):
            if route.startswith('/api/ce/task?'):
                return {'task': {'componentKey': 'ticketing-catalog', 'status': 'SUCCESS', 'analysisId': 'fixture-analysis'}}
            return {'projectStatus': {'status': 'ERROR' if scan_failure else 'OK'}}

        environment = self.environment if local else self.ci_environment()
        stream = io.StringIO()
        with patch.dict(os.environ, environment, clear=True), patch.object(sonar, 'SERVICES', ('catalog',)), \
                patch.object(sonar, 'execute', side_effect=fake_execute), patch.object(sonar, 'check_coverage', return_value={'line': 100}), \
                patch.object(sonar, 'sonar_evidence', side_effect=evidence), \
                patch.object(sonar, 'sonar_request', side_effect=remote_metadata), redirect_stdout(stream):
            sonar.analyze(local=local)
        return calls, stream.getvalue()

    def test_local_evidence_is_separate_and_only_scanner_receives_scoped_token(self):
        calls, output = self.run_analysis_fixture()
        folder = self.repo / 'build/quality/sonar-local' / self.sha
        summary = json.loads((folder / 'summary.json').read_text())
        project = json.loads((folder / 'catalog.json').read_text())
        self.assertEqual(('local', self.sha, 'passed'), (summary['executionContext'], summary['commit'], summary['status']))
        self.assertEqual('local', project['executionContext'])
        self.assertFalse((self.repo / 'build/quality/sonar').exists())
        self.assertNotIn('SONAR_TOKEN', calls[0][1])
        self.assertEqual('analysis-fixture', calls[1][1]['SONAR_TOKEN'])
        self.assertNotIn('SONAR_READ_TOKEN', calls[1][1])
        self.assertIn('-Dsonar.scanner.javaOpts=-Xmx512m', calls[1][0])
        self.assertNotIn('-Dsonar.scanner.javaOpts=-Xmx512m', calls[0][0])
        self.assertNotIn('SONAR_SCANNER_JAVA_OPTS', calls[0][1])
        self.assertNotIn('analysis-fixture', str(calls[1][0]) + output)
        self.assertNotIn('reader-fixture', output)

    def test_ci_keeps_its_existing_output_path_and_context(self):
        self.run_analysis_fixture(local=False)
        summary = json.loads((self.repo / 'build/quality/sonar/summary.json').read_text())
        self.assertEqual('ci', summary['executionContext'])
        self.assertFalse((self.repo / 'build/quality/sonar-local').exists())

    def test_exact_xml_and_hashes_survive_worktree_cleanup(self):
        self.run_analysis_fixture()
        folder = self.repo / 'build/quality/sonar-local' / self.sha
        manifest = json.loads((folder / 'report-manifest.json').read_text())
        reports = manifest['services']['catalog']['reports']
        self.assertEqual(['jacoco', 'junit', 'pit', 'pit'], [item['kind'] for item in reports])
        for report in reports:
            content = (folder / report['path']).read_bytes()
            self.assertEqual(b'<?xml version="1.0"?><fixture/>\r\n', content)
            self.assertEqual(hashlib.sha256(content).hexdigest(), report['sha256'])
        self.assertEqual(1, self.git('worktree', 'list', '--porcelain').count('worktree '))

    def test_failed_gate_preserves_diagnostic_identity_without_success(self):
        with self.assertRaises(sonar.QualityError):
            self.run_analysis_fixture(scan_failure=True)
        folder = self.repo / 'build/quality/sonar-local' / self.sha
        manifest = json.loads((folder / 'report-manifest.json').read_text())
        scanner = manifest['services']['catalog']['scanner']
        self.assertEqual(('fixture-task', 'fixture-analysis', 'ERROR'),
                         (scanner['ceTaskId'], scanner['analysisId'], scanner['qualityGate']))
        self.assertTrue(scanner['diagnosticOnly'])
        self.assertNotIn('must-not-retain', str(manifest))
        self.assertEqual('failed', json.loads((folder / 'summary.json').read_text())['status'])
        self.assertFalse((folder / 'catalog.json').exists())
        self.assertEqual(4, len(manifest['services']['catalog']['reports']))

    def test_successful_build_missing_domain_mutation_xml_fails(self):
        with self.assertRaises(sonar.QualityError):
            self.run_analysis_fixture(on_build=lambda directory:
                (directory / 'domain/model/build/reports/pitest/mutations.xml').unlink())
        folder = self.repo / 'build/quality/sonar-local' / self.sha
        self.assertEqual('failed', json.loads((folder / 'summary.json').read_text())['status'])
        self.assertFalse((folder / 'catalog.json').exists())
        manifest = json.loads((folder / 'report-manifest.json').read_text())
        self.assertTrue(manifest['services']['catalog']['buildCompleted'])
        self.assertEqual(3, len(manifest['services']['catalog']['reports']))

    def test_failed_retry_cannot_retain_previous_success_json(self):
        self.run_analysis_fixture()
        folder = self.repo / 'build/quality/sonar-local' / self.sha
        self.assertTrue((folder / 'catalog.json').exists())
        with self.assertRaises(sonar.QualityError):
            self.run_analysis_fixture(scan_failure=True)
        self.assertFalse((folder / 'catalog.json').exists())
        self.assertEqual('failed', json.loads((folder / 'summary.json').read_text())['status'])

    def test_credential_material_is_not_copied_into_xml_artifacts(self):
        with self.assertRaises(sonar.QualityError):
            self.run_analysis_fixture(on_build=lambda directory:
                (directory / 'build/reports/jacoco/merged/jacoco.xml').write_text('reader-fixture'))
        folder = self.repo / 'build/quality/sonar-local' / self.sha
        self.assertFalse(list(folder.glob('reports/**/*.xml')))
        self.assertEqual('failed', json.loads((folder / 'summary.json').read_text())['status'])

    def test_branch_race_before_scanner_cannot_publish_success(self):
        with self.assertRaises(sonar.QualityError):
            self.run_analysis_fixture(on_build=lambda _: self.git('switch', '-c', 'feature/race'))
        folder = self.repo / 'build/quality/sonar-local' / self.sha
        self.assertEqual('failed', json.loads((folder / 'summary.json').read_text())['status'])
        self.assertFalse((folder / 'catalog.json').exists())

    def test_branch_race_during_remote_wait_cannot_publish_success(self):
        with self.assertRaises(sonar.QualityError):
            self.run_analysis_fixture(on_evidence=lambda: self.git('switch', '-c', 'feature/race'))
        folder = self.repo / 'build/quality/sonar-local' / self.sha
        self.assertEqual('failed', json.loads((folder / 'summary.json').read_text())['status'])
        self.assertFalse((folder / 'catalog.json').exists())

    def test_build_mutation_of_isolated_source_is_rejected(self):
        def mutate(directory):
            (directory.parents[1] / 'source.txt').write_text('modified by build', encoding='utf-8')
        with self.assertRaises(sonar.QualityError):
            self.run_analysis_fixture(on_build=mutate)


if __name__ == '__main__':
    unittest.main()
