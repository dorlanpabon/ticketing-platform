"""Verify local metrics, provisioned panels and traces produced by real business HTTP."""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import json
import math
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
URLS = {'catalog': 'http://127.0.0.1:18080', 'orders': 'http://127.0.0.1:18081',
        'prometheus': 'http://127.0.0.1:19090', 'grafana': 'http://127.0.0.1:13000',
        'tempo': 'http://127.0.0.1:13200'}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def request(service, path, method='GET', body=None, headers=None):
    headers = {'Accept': 'application/json', **(headers or {})}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(URLS[service] + path, method=method, headers=headers,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            data = response.read()
            return response.status, json.loads(data) if data else None
    except urllib.error.HTTPError as error:
        # Error bodies/headers can contain request or credential details; never log them.
        return error.code, None
    except (urllib.error.URLError, TimeoutError) as error:
        raise AssertionError(f'{service}: transport failure ({type(error).__name__})') from None


def wait_for(callback, predicate, description, timeout):
    deadline = time.monotonic() + timeout
    while True:
        result = callback()
        if predicate(result):
            return result
        require(time.monotonic() < deadline, 'Timed out: ' + description)
        time.sleep(2)


def source_state():
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()
    return {'commit': git('rev-parse', 'HEAD'), 'dirty': bool(git('status', '--porcelain'))}


def prometheus_query(expression):
    code, data = request('prometheus', '/api/v1/query?' + urllib.parse.urlencode({'query': expression}))
    require(code == 200 and data.get('status') == 'success', 'Prometheus query failed')
    require(data['data']['resultType'] == 'vector', 'Expected Prometheus instant vector')
    return data['data']['result']


def targets_ready(rows, workers):
    apis = [row for row in rows if row['metric'].get('job') == 'ticketing-apis']
    consumers = [row for row in rows if row['metric'].get('job') == 'ticketing-workers']
    return (len(apis) == 3 and len(consumers) == workers
            and {row['metric']['instance'] for row in apis}
            == {'catalog:8080', 'orders:8080', 'inventory:8080'}
            and len({row['metric']['instance'] for row in consumers}) == workers
            and all(float(row['value'][1]) == 1 for row in apis + consumers))


def grafana_headers():
    password = os.environ.get('GRAFANA_ADMIN_PASSWORD')
    if not password:
        for line in (ROOT / '.env').read_text(encoding='utf-8').splitlines():
            if line.startswith('GRAFANA_ADMIN_PASSWORD='):
                password = line.split('=', 1)[1].strip().strip('\"\'')
                break
    require(bool(password), 'Local Grafana credential is missing')
    token = base64.b64encode(('admin:' + password).encode()).decode()
    return {'Authorization': 'Basic ' + token}


def normalize_id(value, length):
    if re.fullmatch('[0-9a-fA-F]{' + str(length * 2) + '}', value or ''):
        return value.lower()
    try:
        raw = base64.b64decode(value, validate=True)
    except (ValueError, TypeError):
        return None
    return raw.hex() if len(raw) == length else None


def trace_spans(document, trace_id):
    """Allowlist metadata only; never retain arbitrary span attributes or HTTP headers."""
    result = []
    for batch in document.get('batches', document.get('resourceSpans', [])):
        attributes = {item['key']: item.get('value', {}).get('stringValue')
                      for item in batch.get('resource', {}).get('attributes', [])}
        for scope in batch.get('scopeSpans', batch.get('instrumentationLibrarySpans', [])):
            for span in scope.get('spans', []):
                if normalize_id(span.get('traceId'), 16) != trace_id:
                    continue
                result.append({'service': attributes.get('service.name'),
                               'spanId': normalize_id(span.get('spanId'), 8),
                               'parentSpanId': normalize_id(span.get('parentSpanId'), 8),
                               'kind': span.get('kind'),
                               'startTimeUnixNano': int(span.get('startTimeUnixNano', 0))})
    return result


def is_kind(span, name, number):
    return span['kind'] in (number, name, 'SPAN_KIND_' + name)


def linked_http_spans(spans, parent_id, issued_ns):
    fresh = [span for span in spans if span['startTimeUnixNano'] >= issued_ns - 5_000_000_000]
    servers = [span for span in fresh if is_kind(span, 'SERVER', 2)]
    clients = [span for span in fresh if span['service'] == 'orders' and is_kind(span, 'CLIENT', 3)]
    return (any(span['service'] == 'orders' and span['parentSpanId'] == parent_id for span in servers)
            and any(server['service'] == 'inventory' and server['parentSpanId'] == client['spanId']
                    for server in servers for client in clients))


def get_trace(trace_id):
    code, body = request('tempo', '/api/traces/' + trace_id)
    require(code in (200, 404), f'Tempo trace lookup returned HTTP {code}')
    return trace_spans(body, trace_id) if code == 200 else []


def business_headers(identity, trace_id=None, parent_id=None):
    headers = {'Authorization': 'Bearer ' + identity, 'Idempotency-Key': uuid.uuid4().hex}
    if trace_id:
        headers['traceparent'] = f'00-{trace_id}-{parent_id}-01'
    return headers


def verify_business_traces(timeout):
    catalog_trace, catalog_parent = uuid.uuid4().hex, uuid.uuid4().hex[:16]
    issued_ns = time.time_ns()
    code, event = request('catalog', '/api/v1/events', 'POST', {
        'name': 'Observability ' + uuid.uuid4().hex, 'venue': 'Local evidence',
        'startsAt': (datetime.now(timezone.utc) + timedelta(days=30)).isoformat(), 'capacity': 1},
        business_headers('local-admin', catalog_trace, catalog_parent))
    require(code == 202, f'Catalog business request returned HTTP {code}')
    event_id = event['eventId']
    wait_for(lambda: request('catalog', '/api/v1/events/' + event_id,
                            headers=business_headers('local-customer-1')),
             lambda response: response[0] == 200 and response[1]['status'] == 'ACTIVE',
             'isolated event provisioning', timeout)
    catalog_spans = wait_for(lambda: get_trace(catalog_trace), lambda spans: any(
        span['service'] == 'catalog' and is_kind(span, 'SERVER', 2)
        and span['parentSpanId'] == catalog_parent
        and span['startTimeUnixNano'] >= issued_ns - 5_000_000_000 for span in spans),
        'real Catalog HTTP trace', timeout)
    code, order = request('orders', '/api/v1/orders', 'POST', {'eventId': event_id, 'ticketIds': ['1']},
                          business_headers('local-customer-1'))
    require(code == 202, f'Orders business request returned HTTP {code}')
    order_id = order['orderId']
    order_path = '/api/v1/orders/' + order_id
    wait_for(lambda: request('orders', order_path, headers=business_headers('local-customer-1')),
             lambda response: response[0] == 200 and response[1]['workflowStatus'] == 'AWAITING_CONFIRMATION'
             and response[1]['status'] == 'RESERVED', 'asynchronous inventory reservation', timeout)
    orders_trace, orders_parent = uuid.uuid4().hex, uuid.uuid4().hex[:16]
    order_issued_ns = time.time_ns()
    code, held = request('orders', order_path, headers=business_headers('local-customer-1', orders_trace, orders_parent))
    require(code == 200 and held['orderId'] == order_id and held['workflowStatus'] == 'AWAITING_CONFIRMATION'
            and held['status'] == 'RESERVED', 'Traced order GET did not return the held reservation')
    orders_spans = wait_for(lambda: get_trace(orders_trace),
                           lambda spans: linked_http_spans(spans, orders_parent, order_issued_ns),
                           'linked Orders client and Inventory server spans', timeout)
    return {'eventId': event_id, 'orderId': order_id,
            'catalog': {'traceId': catalog_trace, 'spans': catalog_spans},
            'ordersToInventory': {'traceId': orders_trace, 'spans': orders_spans, 'operation': 'GET /api/v1/orders/{orderId}'},
            'transport': 'real HTTP business requests with incoming W3C traceparent',
            'sqsPropagationVerified': False}


def verify_dashboard(timeout):
    headers = grafana_headers()
    code, document = request('grafana', '/api/dashboards/uid/ticketing-runtime', headers=headers)
    require(code == 200, f'Grafana dashboard returned HTTP {code}')
    require(document['meta'].get('provisioned') is True, 'Dashboard is not provisioned')
    panels = document['dashboard']['panels']
    require(len(panels) == 5, 'Expected exactly five dashboard panels')
    result = []
    for panel in panels:
        require(panel['datasource']['uid'] == 'prometheus', 'Unexpected panel datasource')
        for target in panel['targets']:
            rows = wait_for(lambda: prometheus_query(target['expr']), lambda values: any(
                math.isfinite(float(row['value'][1])) for row in values),
                'finite metric values for panel ' + str(panel['id']), timeout)
            result.append({'panelId': panel['id'], 'title': panel['title'], 'seriesCount': len(rows),
                           'finiteSeriesCount': sum(math.isfinite(float(row['value'][1])) for row in rows)})
    for uid in ('prometheus', 'tempo'):
        code, datasource = request('grafana', '/api/datasources/uid/' + uid, headers=headers)
        require(code == 200 and datasource['type'] == uid, 'Grafana datasource is missing: ' + uid)
    return {'uid': 'ticketing-runtime', 'provisioned': True, 'panels': result,
            'datasourceUids': ['prometheus', 'tempo']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timeout', type=int, default=120)
    parser.add_argument('--expected-workers', type=int, default=4)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/quality/observability.json')
    args = parser.parse_args()
    require(1 <= args.timeout <= 300 and args.expected_workers >= 3, 'Invalid bounded probe settings')
    report = {'schemaVersion': 1, 'executionContext': 'local', 'source': source_state(),
              'startedAt': datetime.now(timezone.utc).isoformat(), 'checks': {},
              'limitations': ['Local emulator runtime; not AWS deployment evidence.',
                              'HTTP trace propagation is checked; SQS trace propagation is not.']}
    try:
        rows = wait_for(lambda: prometheus_query('up'), lambda values: targets_ready(values, args.expected_workers),
                        'three API and expected worker scrape targets UP', args.timeout)
        report['checks']['prometheus'] = {'targets': [
            {'job': row['metric']['job'], 'instance': row['metric']['instance'], 'up': float(row['value'][1])}
            for row in rows if row['metric'].get('job') in ('ticketing-apis', 'ticketing-workers')]}
        report['checks']['traces'] = verify_business_traces(args.timeout)
        report['checks']['grafana'] = verify_dashboard(args.timeout)
        report['status'] = 'passed'
    except (AssertionError, KeyError, ValueError, OSError) as error:
        report['status'] = 'failed'
        # Only our controlled assertions are emitted; unexpected exception contents are omitted.
        report['error'] = str(error) if isinstance(error, AssertionError) else type(error).__name__
    report['sourceAtEnd'] = source_state()
    report['sameCommitThroughout'] = report['source']['commit'] == report['sourceAtEnd']['commit']
    report['finishedAt'] = datetime.now(timezone.utc).isoformat()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': report['status'], 'report': str(args.output), 'error': report.get('error')}))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
