import base64
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('observability', Path(__file__).resolve().parents[1] / 'verify_observability.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ObservabilityTest(unittest.TestCase):
    def test_missing_or_down_workers_cannot_pass(self):
        rows = [{'metric': {'job': 'ticketing-apis', 'instance': service + ':8080'}, 'value': [1, '1']}
                for service in ('catalog', 'orders', 'inventory')]
        self.assertFalse(probe.targets_ready(rows, 4))
        rows.extend({'metric': {'job': 'ticketing-workers', 'instance': str(index)}, 'value': [1, '1']}
                    for index in range(4))
        self.assertTrue(probe.targets_ready(rows, 4))
        rows[-1]['value'][1] = '0'
        self.assertFalse(probe.targets_ready(rows, 4))

    def test_trace_parser_redacts_attributes_and_rejects_another_trace(self):
        trace = '12' * 16
        document = {'resourceSpans': [{'resource': {'attributes': [
            {'key': 'service.name', 'value': {'stringValue': 'orders'}},
            {'key': 'secret', 'value': {'stringValue': 'must-not-survive'}}]}, 'scopeSpans': [{'spans': [
                {'traceId': base64.b64encode(bytes.fromhex(trace)).decode(), 'spanId': '34' * 8,
                 'kind': 2, 'attributes': [{'key': 'authorization', 'value': 'must-not-survive'}]},
                {'traceId': '56' * 16}]}]}]}
        spans = probe.trace_spans(document, trace)
        self.assertEqual(1, len(spans))
        self.assertEqual('orders', spans[0]['service'])
        self.assertNotIn('must-not-survive', str(spans))

    def test_shared_trace_without_client_parent_link_is_insufficient(self):
        spans = [dict(service='orders', kind=2, parentSpanId='parent', spanId='server', startTimeUnixNano=10**12),
                 dict(service='orders', kind=3, parentSpanId='server', spanId='client', startTimeUnixNano=10**12),
                 dict(service='inventory', kind=2, parentSpanId='wrong', spanId='inventory', startTimeUnixNano=10**12)]
        self.assertFalse(probe.linked_http_spans(spans, 'parent', 10**12))
        spans[-1]['parentSpanId'] = 'client'
        self.assertTrue(probe.linked_http_spans(spans, 'parent', 10**12))
        self.assertFalse(probe.linked_http_spans(spans, 'parent', 2 * 10**12))


if __name__ == '__main__':
    unittest.main()
