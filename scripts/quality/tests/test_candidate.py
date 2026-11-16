import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from candidate import acceptance_evidence, scan_image_evidence
from quality import QualityError


class AcceptanceEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.report = self.root / 'acceptance.json'
        self.value = {'commit': 'a' * 40, 'tree': 'b' * 40, 'dirty': False, 'checks': {}}
        self.children = {
            'integration': {'eventId': 'event', 'orderId': 'order', 'assertions': ['durable202', 'idempotency', 'deadline', 'owner', 'confirmation', 'courtesy', 'all-or-none', 'counters']},
            'architecture': {'services': {service: {'tests': 3, 'report': service + '.xml', 'sha256': 'c' * 64}
                                          for service in ('catalog', 'orders', 'inventory')},
                             'rules': ['domain-dependencies', 'bounded-context-isolation', 'nonblocking-reactive-code']},
            'concurrency': {'eventId': 'event', 'buyers': 100, 'winners': 1, 'rejected': 99},
            'recovery': {'eventId': 'event', 'orderId': 'order', 'scenario': 'Inventory unavailable then restored',
                         'consumerReplicas': 2, 'deadlinePreserved': True},
            'containerSmoke': {'services': ['catalog', 'orders', 'inventory'], 'readiness': 'UP'},
            'expiry': {'eventId': 'event', 'orderId': 'order', 'expiresAt': '2026-11-16T00:10:00Z',
                       'timerProcessingLagSeconds': 0.52, 'periodicRecoveryStopped': True, 'httpLazyExpiryDisabled': True,
                       'durableExpiredState': 'RESERVATION_EXPIRED', 'soldTicketPreserved': True},
        }
        for check, child in self.children.items():
            child['status'] = 'passed'
            self.write_child(check, child)
            self.value['checks'][check] = {'status': 'passed', 'evidence': check + '.json'}

    def write_child(self, name, value):
        (self.root / (name + '.json')).write_text(json.dumps(value), encoding='utf-8')

    def verify(self):
        self.report.write_text(json.dumps(self.value), encoding='utf-8')
        return acceptance_evidence(self.report, self.root, 'a' * 40, 'b' * 40)

    def test_clean_exact_candidate_hashes_actual_reports(self):
        proofs = self.verify()
        self.assertEqual(6, len(proofs))
        self.assertEqual(64, len(proofs['recovery']['sha256']))

    def test_dirty_or_missing_clean_identity_is_rejected(self):
        for dirty in (True, None, 'false'):
            self.value['dirty'] = dirty
            with self.assertRaises(QualityError):
                self.verify()

    def test_other_commit_or_tree_is_rejected(self):
        self.value['tree'] = 'c' * 40
        with self.assertRaises(QualityError):
            self.verify()

    def test_parent_pass_cannot_override_failed_or_malformed_children(self):
        for name, original in self.children.items():
            for child in (None, [], 'passed', {}, {'status': 'passed'}, {**original, 'status': 'failed'},
                          {**original, 'status': True}, {**original, 'error': 'failed assertion'}):
                with self.subTest(name=name, child=child):
                    self.write_child(name, child)
                    with self.assertRaises(QualityError):
                        self.verify()
            (self.root / (name + '.json')).write_text('{invalid', encoding='utf-8')
            with self.assertRaises(QualityError):
                self.verify()
            self.write_child(name, original)

    def test_missing_child_fields_and_false_assertions_fail_closed(self):
        for name, original in self.children.items():
            for key in original:
                with self.subTest(name=name, missing=key):
                    self.write_child(name, {field: value for field, value in original.items() if field != key})
                    with self.assertRaises(QualityError):
                        self.verify()
            self.write_child(name, original)
        cases = [('concurrency', 'winners', True), ('concurrency', 'winners', 2), ('recovery', 'consumerReplicas', 1),
                 ('expiry', 'timerProcessingLagSeconds', float('nan')), ('expiry', 'timerProcessingLagSeconds', -1),
                 ('expiry', 'soldTicketPreserved', False), ('integration', 'assertions', ['counters']),
                 ('architecture', 'services', {'catalog': None}), ('containerSmoke', 'services', ['catalog'])]
        for name, field, value in cases:
            with self.subTest(name=name, field=field, value=value):
                self.write_child(name, {**self.children[name], field: value})
                with self.assertRaises(QualityError):
                    self.verify()
            self.write_child(name, self.children[name])

    def test_invalid_parent_shape_and_evidence_paths_fail_closed(self):
        original = self.value['checks']
        for checks in (None, [], 'passed', {'integration': None}, {'integration': {'status': 'passed', 'evidence': []}},
                       {'integration': {'status': 'passed', 'evidence': '../outside.json'}}):
            with self.subTest(checks=checks):
                self.value['checks'] = checks
                with self.assertRaises(QualityError):
                    self.verify()
        self.value['checks'] = original
        for raw in ('null', '[]', '{invalid'):
            self.report.write_text(raw, encoding='utf-8')
            with self.assertRaises(QualityError):
                acceptance_evidence(self.report, self.root, 'a' * 40, 'b' * 40)

    def test_missing_and_failed_checks_are_rejected(self):
        self.value['checks']['concurrency']['status'] = 'failed'
        with self.assertRaises(QualityError):
            self.verify()
        self.value['checks']['concurrency']['status'] = 'passed'
        self.value['checks']['concurrency']['evidence'] = 'missing.json'
        with self.assertRaises(QualityError):
            self.verify()


class ImageEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.report = self.root / 'security.json'
        self.sbom = self.root / 'sbom.json'
        self.scan_document = {'Results': [
            {'Class': 'os-pkgs', 'Type': 'alpine', 'Packages': [{'Name': 'busybox', 'Version': '1.37'}]},
            {'Class': 'lang-pkgs', 'Type': 'jar', 'Packages': [{'Name': 'org.example/library', 'Version': '1.0'}]},
        ]}
        self.sbom_document = {'bomFormat': 'CycloneDX', 'components': [{'name': 'library', 'type': 'library'}]}
        self.calls = []

    def execute(self, arguments, **_):
        self.calls.append(arguments)
        document, path = (self.scan_document, self.report) if arguments[1] == 'image' else (self.sbom_document, self.sbom)
        if document is not None:
            path.write_text(document if isinstance(document, str) else json.dumps(document), encoding='utf-8')

    def verify(self, image='catalog:test'):
        self.calls = []
        with patch('candidate.execute', side_effect=self.execute):
            scan_image_evidence('trivy', image, self.report, self.sbom)

    def test_valid_inventory_converts_same_report_for_all_three_images(self):
        for service in ('catalog', 'orders', 'inventory'):
            with self.subTest(service=service):
                self.verify(service + ':test')
                self.assertEqual(['image', 'convert'], [call[1] for call in self.calls])
                scan, convert = self.calls
                self.assertEqual('memory', scan[scan.index('--cache-backend') + 1])
                self.assertIn('--list-all-pkgs', scan)
                self.assertEqual('HIGH,CRITICAL', scan[scan.index('--severity') + 1])
                self.assertEqual('1', scan[scan.index('--exit-code') + 1])
                self.assertEqual('', scan[scan.index('--ignorefile') + 1])
                self.assertEqual(str(self.report), convert[-1])
                self.assertEqual('cyclonedx', convert[convert.index('--format') + 1])

    def test_empty_malformed_and_incomplete_reports_block_conversion(self):
        valid = self.scan_document
        for document in (None, '', '{', [], {}, {'Results': None}, {'Results': []}, {'Results': [None]},
                         {'Results': valid['Results'][:1]}, {'Results': valid['Results'][1:]}):
            with self.subTest(document=document):
                self.scan_document = document
                with self.assertRaises(QualityError):
                    self.verify()
                self.assertEqual(['image'], [call[1] for call in self.calls])

    def test_missing_empty_and_malformed_package_inventories_fail_closed(self):
        for index in (0, 1):
            original = self.scan_document['Results'][index]['Packages']
            for packages in (None, [], {}, [None], [{}], [{'Name': 'pkg', 'Version': ''}]):
                with self.subTest(index=index, packages=packages):
                    self.scan_document['Results'][index]['Packages'] = packages
                    with self.assertRaises(QualityError):
                        self.verify()
            self.scan_document['Results'][index]['Packages'] = original

    def test_findings_or_malformed_vulnerabilities_reject_even_successful_exit(self):
        for findings in ([{'Severity': 'HIGH'}], [{'Severity': 'CRITICAL'}], [{}], [None], {}, '', False):
            with self.subTest(findings=findings):
                self.scan_document['Results'][1]['Vulnerabilities'] = findings
                with self.assertRaises(QualityError):
                    self.verify()
                self.assertEqual(1, len(self.calls))

    def test_missing_or_invalid_sbom_is_rejected(self):
        for document in (None, '', '{', [], {}, {'bomFormat': 'SPDX', 'components': [{'name': 'x', 'type': 'library'}]},
                         {'bomFormat': 'CycloneDX', 'components': []}, {'bomFormat': 'CycloneDX', 'components': [{}]},
                         {'bomFormat': 'CycloneDX', 'components': 'invalid'}):
            with self.subTest(document=document):
                self.sbom_document = document
                with self.assertRaises(QualityError):
                    self.verify()
                self.assertEqual(2, len(self.calls))

    def test_stale_reports_cannot_satisfy_new_scan(self):
        self.report.write_text(json.dumps(self.scan_document), encoding='utf-8')
        self.sbom.write_text(json.dumps(self.sbom_document), encoding='utf-8')
        self.scan_document = None
        with self.assertRaises(QualityError):
            self.verify()
        self.assertFalse(self.report.exists())
        self.assertFalse(self.sbom.exists())

    def test_scanner_failure_blocks_conversion(self):
        with patch('candidate.execute', side_effect=QualityError('scanner failed')) as execute:
            with self.assertRaises(QualityError):
                scan_image_evidence('trivy', 'catalog:test', self.report, self.sbom)
            self.assertEqual(1, execute.call_count)


if __name__ == '__main__':
    unittest.main()
