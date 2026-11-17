import base64
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('observability', Path(__file__).resolve().parents[1] / 'verify_observability.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ObservabilityTest(unittest.TestCase):
    def test_asynchronous_creation_is_followed_by_a_distinct_traced_get(self):
        traces, polls, traced_methods = {}, [], []
        held = {'orderId': 'order', 'workflowStatus': 'AWAITING_CONFIRMATION', 'status': 'RESERVED'}
        pending = held | {'workflowStatus': 'RESERVING', 'status': 'AVAILABLE'}
        issued = 10**12

        def request(service, path, method='GET', body=None, headers=None):
            headers = headers or {}
            if service == 'catalog' and method == 'POST':
                _, trace, parent, _ = headers['traceparent'].split('-')
                traces[trace] = [dict(service='catalog', kind=2, spanId='1'*16, parentSpanId=parent, startTimeUnixNano=issued)]
                return 202, {'eventId': 'event'}
            if service == 'catalog':
                return 200, {'eventId': 'event', 'status': 'ACTIVE'}
            if method == 'POST':
                return 202, pending
            self.assertEqual(('orders', '/api/v1/orders/order', 'GET'), (service, path, method))
            if 'traceparent' not in headers:
                polls.append(path)
                return 200, pending if len(polls) == 1 else held
            self.assertGreaterEqual(len(polls), 2)
            _, trace, parent, _ = headers['traceparent'].split('-')
            traced_methods.append(method)
            traces[trace] = [dict(service='orders', kind=2, spanId='2'*16, parentSpanId=parent, startTimeUnixNano=issued),
                             dict(service='orders', kind=3, spanId='3'*16, parentSpanId='2'*16, startTimeUnixNano=issued),
                             dict(service='inventory', kind=2, spanId='4'*16, parentSpanId='3'*16, startTimeUnixNano=issued)]
            return 200, held

        def wait(callback, predicate, description, timeout):
            for _ in range(3):
                value = callback()
                if predicate(value):
                    return value
            raise AssertionError('No observed result for ' + description)

        with patch.object(probe, 'request', side_effect=request), patch.object(probe, 'get_trace', side_effect=lambda trace: traces.get(trace, [])), \
                patch.object(probe, 'wait_for', side_effect=wait), patch.object(probe.time, 'time_ns', return_value=issued):
            result = probe.verify_business_traces(30)
        self.assertEqual(['GET'], traced_methods)
        self.assertEqual(('event', 'order'), (result['eventId'], result['orderId']))
        self.assertEqual('catalog', result['catalog']['spans'][0]['service'])
        self.assertEqual('GET /api/v1/orders/{orderId}', result['ordersToInventory']['operation'])
        self.assertFalse(result['sqsPropagationVerified'])

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
