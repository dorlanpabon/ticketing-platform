import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from candidate import acceptance_evidence
from quality import QualityError


class AcceptanceEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.report = self.root / 'acceptance.json'
        self.value = {'commit': 'a' * 40, 'tree': 'b' * 40, 'dirty': False, 'checks': {}}
        for check in ('integration', 'architecture', 'concurrency', 'recovery', 'containerSmoke'):
            (self.root / (check + '.json')).write_text('{"status":"passed"}', encoding='utf-8')
            self.value['checks'][check] = {'status': 'passed', 'evidence': check + '.json'}

    def verify(self):
        self.report.write_text(json.dumps(self.value), encoding='utf-8')
        return acceptance_evidence(self.report, self.root, 'a' * 40, 'b' * 40)

    def test_clean_exact_candidate_hashes_actual_reports(self):
        proofs = self.verify()
        self.assertEqual(5, len(proofs))
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

    def test_missing_and_failed_checks_are_rejected(self):
        self.value['checks']['concurrency']['status'] = 'failed'
        with self.assertRaises(QualityError):
            self.verify()
        self.value['checks']['concurrency']['status'] = 'passed'
        self.value['checks']['concurrency']['evidence'] = 'missing.json'
        with self.assertRaises(QualityError):
            self.verify()


if __name__ == '__main__':
    unittest.main()
