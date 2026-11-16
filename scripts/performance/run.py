"""Bounded local k6 runner. plan/validate do not call application APIs; run seeds data."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = Path(__file__).resolve().parent
RESULTS = ROOT / 'evidence/local/performance'
K6_IMAGE = 'grafana/k6:2.4.0@sha256:d41bb79cd8de5a7ad619b53570e3712633e8bff0937010a5c6c57a4747e7a276'
URLS = {'catalog': 'http://127.0.0.1:18080', 'orders': 'http://127.0.0.1:18081', 'inventory': 'http://127.0.0.1:18082'}


@dataclass(frozen=True)
class Config:
    profile: str
    scenario: str
    vus: int
    duration_seconds: int
    max_rps: int
    capacity: int
    events: int
    seed: int
    generator_memory_mib: int
    generator_cpus: float
    cohort_tickets: int = 1


def configuration(args, env):
    def configured(value, default):
        return default if value is None else value

    full = args.profile == 'full'
    if full and env.get('PERF_FULL') != '1':
        raise ValueError('Full load requires PERF_FULL=1; it is not the default smoke run')
    cohort = args.scenario == 'cohort'
    if cohort and env.get('PERF_STRESS') != '1':
        raise ValueError('The synchronized cohort bypasses pacing and requires PERF_STRESS=1')
    result = Config(args.profile, args.scenario, configured(args.vus, 1000 if full else 100 if cohort else 50),
                    120 if cohort else configured(args.duration_seconds, 1800 if full else 30),
                    configured(args.max_rps, 100 if full else 20), configured(args.capacity, 100000 if full else 2000),
                    args.events, args.seed, configured(args.generator_memory_mib, 1536 if full else 512),
                    configured(args.generator_cpus, 2 if full else 1), args.cohort_tickets)
    if not 1 <= result.vus <= 1000 or not 1 <= result.max_rps <= 1000:
        raise ValueError('VUs and paced request budget must be in 1..1000')
    if not full and not cohort and (result.vus > 50 or result.duration_seconds > 30 or result.max_rps > 20):
        raise ValueError('Smoke is capped at 50 VUs, 30 seconds and 20 requests/s; use the explicit full profile')
    if cohort and result.vus not in (100, 1000):
        raise ValueError('Cohort size must be 100 or 1000 attempts')
    if not 1 <= result.cohort_tickets <= result.vus or (not cohort and result.cohort_tickets != 1):
        raise ValueError('Cohort tickets must be in 1..VUs and only apply to the cohort scenario')
    if not 1 <= result.duration_seconds <= 1800 or not 1 <= result.capacity <= 100000 or not 1 <= result.events <= 10:
        raise ValueError('Invalid duration or dataset bounds')
    if not 128 <= result.generator_memory_mib <= 4096 or not 0.25 <= result.generator_cpus <= 4:
        raise ValueError('Generator limits must be 128..4096 MiB and 0.25..4 CPUs')
    if not 0 <= result.seed <= 2147483647:
        raise ValueError('Seed must be a nonnegative 32-bit integer')
    return result


def command(arguments, timeout=60):
    result = subprocess.run(arguments, cwd=ROOT, text=True, encoding='utf-8', errors='replace', capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{arguments[0]} failed ({result.returncode}): {result.stderr[-1200:]}')
    return result.stdout.strip()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')


def utc():
    return datetime.now(timezone.utc).isoformat()


def http(service, path, method='GET', body=None, identity='local-customer-1', key=None):
    headers = {'Authorization': 'Bearer ' + identity, 'Content-Type': 'application/json'}
    if key:
        headers['Idempotency-Key'] = key
    request = urllib.request.Request(URLS[service] + path, data=json.dumps(body).encode() if body is not None else None,
                                     headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            content = response.read()
            return response.status, json.loads(content) if content else None
    except urllib.error.HTTPError as error:
        content = error.read()
        return error.code, json.loads(content) if content else None


def create_event(run_id, index, capacity):
    status, body = http('catalog', '/api/v1/events', 'POST', {
        'name': f'Performance {run_id} {index}', 'venue': 'Local load verification',
        'startsAt': (datetime.now(timezone.utc) + timedelta(days=30)).isoformat(), 'capacity': capacity,
    }, 'local-admin', f'{run_id}-event-{index}')
    if status != 202 or not body or 'eventId' not in body:
        raise RuntimeError(f'Event preparation failed with HTTP {status}')
    event_id = body['eventId']
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        status, current = http('catalog', '/api/v1/events/' + event_id)
        if status == 200 and current['status'] == 'ACTIVE':
            return {'eventId': event_id, 'capacity': capacity}
        time.sleep(0.5)
    raise RuntimeError('Provisioning did not become ACTIVE within 300 seconds; no load was started')


def seed_dataset(config, run_id):
    for service in URLS:
        status, body = http(service, '/actuator/health/readiness')
        if status != 200 or body.get('status') != 'UP':
            raise RuntimeError(f'{service} is not ready; no load was started')
    dataset = {'runId': run_id, 'seed': config.seed, 'createdAt': utc(), 'events': []}
    if config.scenario != 'cohort':
        for index in range(config.events):
            print(f'Preparing dataset event {index + 1}/{config.events}: {config.capacity} seats', flush=True)
            dataset['events'].append(create_event(run_id, index, config.capacity))
    if config.scenario in ('mixed', 'hotseat', 'cohort'):
        dataset['hotEvent'] = create_event(run_id, 'hot', config.cohort_tickets if config.scenario == 'cohort' else 1)
    dataset['cohortStartsAt'] = int(time.time() * 1000) + 30000
    return dataset


def runtime_inventory():
    compose = ['docker', 'compose', '-f', str(ROOT / 'docker-compose.yml')]
    identifiers = command([*compose, 'ps', '--all', '--quiet']).splitlines()
    if not identifiers:
        raise RuntimeError('The application Compose project is not running')
    inspected = json.loads(command(['docker', 'inspect', *identifiers]))
    records = []
    networks = None
    for item in inspected:
        service = item['Config'].get('Labels', {}).get('com.docker.compose.service', '')
        records.append({'id': item['Id'], 'service': service, 'imageId': item['Image'], 'name': item['Name'].lstrip('/'),
                        'running': item['State']['Running'], 'memoryLimitBytes': item['HostConfig']['Memory'],
                        'nanoCpus': item['HostConfig']['NanoCpus'], 'oomKilled': item['State']['OOMKilled']})
        if service in URLS and item['State']['Running']:
            attached = set(item['NetworkSettings']['Networks'])
            networks = attached if networks is None else networks & attached
    if len([item for item in records if item['service'] in URLS and item['running']]) != 3 or not networks:
        raise RuntimeError('All three APIs must be running on a shared Compose network')
    docker = json.loads(command(['docker', 'info', '--format', '{{json .}}']))
    return {'docker': {key: docker.get(key) for key in ('ServerVersion', 'OperatingSystem', 'Architecture', 'NCPU', 'MemTotal')},
            'containers': records, 'network': sorted(networks)[0]}


def check_resources(config, inventory):
    running = [item for item in inventory['containers'] if item['running']]
    if any(item['service'] in ('sonar', 'sonar-db') for item in running):
        raise ValueError('Stop the optional quality profile before benchmarking; the runner does not stop services')
    limited = sum(item['memoryLimitBytes'] for item in running)
    memory = inventory['docker'].get('MemTotal') or 0
    if memory and limited + config.generator_memory_mib * 1024 * 1024 > memory * 0.9:
        raise ValueError('Configured service limits plus generator exceed 90% of Docker VM memory; adjust resources explicitly')


def docker_arguments(config, directory, network):
    args = ['docker', 'run', '--network', network, '--cpus', str(config.generator_cpus), '--memory', f'{config.generator_memory_mib}m',
            '--read-only', '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
            '--user', f'{os.getuid() if hasattr(os, "getuid") else 1000}:{os.getgid() if hasattr(os, "getgid") else 1000}',
            '--mount', f'type=bind,source={SCRIPTS},target=/scripts,readonly', '--mount', f'type=bind,source={directory},target=/results']
    environment = {'SCENARIO': config.scenario, 'VUS': config.vus, 'DURATION': f'{config.duration_seconds}s',
                   'MAX_RPS': config.max_rps, 'SEED': config.seed, 'K6_NO_USAGE_REPORT': 'true',
                   'PERF_FULL': '1' if config.profile == 'full' else '0', 'PERF_STRESS': '1' if config.scenario == 'cohort' else '0'}
    for key, value in environment.items():
        args.extend(['--env', f'{key}={value}'])
    return args


def counter_invariant(snapshot):
    values = [snapshot.get(key) for key in ('available', 'reserved', 'pendingConfirmation', 'sold', 'complimentary')]
    return all(type(value) is int and value >= 0 for value in values) and sum(values) == snapshot.get('capacity')


def inspected_duration_seconds(value):
    match = re.fullmatch(r'(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?', value)
    if not match or not any(match.groups()):
        raise ValueError('Unexpected k6 duration representation')
    return sum(int(amount or 0) * scale for amount, scale in zip(match.groups(), (3600, 60, 1)))


def parse_attempts(path):
    attempts = []
    for line in path.read_text(encoding='utf-8').splitlines():
        start = line.find('{')
        if start < 0:
            continue
        try:
            value = json.loads(line[start:])
        except json.JSONDecodeError:
            continue
        if value.get('kind') == 'cohort_attempt':
            attempts.append(value)
    return attempts


def cohort_inventory(event_id, expected_tickets):
    tickets = {}
    cursors = set()
    cursor = None
    for _ in range(expected_tickets):
        query = {'limit': 100}
        if cursor:
            query['cursor'] = cursor
        status, page = http('inventory', f'/api/v1/events/{event_id}/tickets?' + urllib.parse.urlencode(query))
        if status != 200 or not isinstance(page, dict) or not isinstance(page.get('items'), list) or not page['items']:
            raise RuntimeError('Cohort authoritative ticket page is missing or malformed')
        for ticket in page['items']:
            if not isinstance(ticket, dict) or ticket.get('eventId') != event_id or not isinstance(ticket.get('ticketId'), str) \
                    or ticket['ticketId'] in tickets or ticket.get('status') != 'RESERVED':
                raise RuntimeError('Cohort authoritative tickets must be unique reservations')
            tickets[ticket['ticketId']] = ticket['status']
        cursor = page.get('nextCursor')
        if cursor is None:
            break
        if not isinstance(cursor, str) or not cursor or cursor in cursors:
            raise RuntimeError('Cohort ticket pagination did not advance')
        cursors.add(cursor)
    else:
        raise RuntimeError('Cohort ticket pagination exceeded its bounded budget')
    if set(tickets) != {str(index) for index in range(1, expected_tickets + 1)}:
        raise RuntimeError('Cohort authoritative ticket IDs do not match the prepared capacity')
    return tickets


def audit(config, dataset, directory):
    result = {'availability': [], 'cohort': None}
    for event in dataset['events'] + ([dataset['hotEvent']] if 'hotEvent' in dataset else []):
        status, snapshot = http('inventory', f'/api/v1/events/{event["eventId"]}/availability')
        if status != 200 or not counter_invariant(snapshot):
            raise RuntimeError('Post-load authoritative inventory counters failed their invariant')
        result['availability'].append(snapshot)
    if config.scenario != 'cohort':
        return result
    if dataset['hotEvent']['capacity'] != config.cohort_tickets:
        raise RuntimeError('Cohort dataset capacity differs from the configured ticket count')
    attempts = parse_attempts(directory / 'cohort-attempts.jsonl')
    if len(attempts) != config.vus or any(type(item.get('vu')) is not int for item in attempts) \
            or {item['vu'] for item in attempts} != set(range(1, config.vus + 1)):
        raise RuntimeError('Cohort did not record exactly one request for every configured VU')
    if any(item.get('ticketId') != str((item['vu'] - 1) % config.cohort_tickets + 1) for item in attempts):
        raise RuntimeError('Cohort attempts did not use the fair configured ticket assignment')
    if any(item['status'] not in (202, 409) for item in attempts):
        raise RuntimeError('Cohort contains an unexpected HTTP result')
    accepted = [item for item in attempts if item['status'] == 202]
    if not accepted or any(not item.get('orderId') for item in accepted) or len({item['orderId'] for item in accepted}) != len(accepted):
        raise RuntimeError('Cohort accepted order IDs must be present and unique')
    deadline = time.monotonic() + 120
    pending = accepted
    outcomes = {}

    def read_order(item):
        if time.monotonic() > deadline:
            return item, None
        identity = 'local-customer-1' if item['vu'] % 2 else 'local-customer-2'
        status, order = http('orders', '/api/v1/orders/' + item['orderId'], identity=identity)
        if status == 200 and (order.get('orderId') != item['orderId'] or order.get('eventId') != dataset['hotEvent']['eventId']
                              or order.get('requestedTicketIds') != [item['ticketId']]):
            raise RuntimeError('Cohort order identity or requested ticket differs from its attempt')
        return item, order if status == 200 else None

    with ThreadPoolExecutor(max_workers=4) as executor:
        while pending and time.monotonic() < deadline:
            remaining = []
            for item, order in executor.map(read_order, pending):
                if not order or order['workflowStatus'] == 'RESERVING':
                    remaining.append(item)
                else:
                    outcomes[item['orderId']] = order
            pending = remaining
            if pending:
                time.sleep(0.5)
    winners = [item for item in outcomes.values() if item['status'] == 'RESERVED' and item['workflowStatus'] == 'AWAITING_CONFIRMATION'
               and item['activeTicketIds'] == item['requestedTicketIds']]
    rejected = [item for item in outcomes.values() if item['workflowStatus'] == 'REJECTED' and item['status'] == 'AVAILABLE'
                and item['activeTicketIds'] == []]
    winners_by_ticket = {}
    for winner in winners:
        winners_by_ticket.setdefault(winner['activeTicketIds'][0], []).append(winner['orderId'])
    status, authoritative = http('inventory', f'/api/v1/events/{dataset["hotEvent"]["eventId"]}/availability')
    tickets = cohort_inventory(dataset['hotEvent']['eventId'], config.cohort_tickets)
    result['cohort'] = {'attempts': len(attempts), 'http202': len(accepted), 'http409': len(attempts) - len(accepted),
                        'reservedWinners': len(winners), 'asynchronousRejections': len(rejected), 'unresolved': len(pending),
                        'expectedWinners': config.cohort_tickets, 'rejectedBuyers': len(attempts) - len(accepted) + len(rejected),
                        'semantics': 'concurrent-reservations-not-completed-sales', 'winnersByTicket': winners_by_ticket,
                        'authoritativeTickets': tickets,
                        'requestStartSkewMs': max(item['sentAt'] for item in attempts) - min(item['sentAt'] for item in attempts),
                        'authoritativeAvailability': authoritative}
    write_json(directory / 'cohort-audit.json', result['cohort'])
    if pending or len(winners) != config.cohort_tickets or len(winners) + len(rejected) != len(accepted) or status != 200 \
            or set(winners_by_ticket) != set(tickets) or any(len(owners) != 1 for owners in winners_by_ticket.values()) \
            or not counter_invariant(authoritative) or authoritative['capacity'] != config.cohort_tickets \
            or authoritative['reserved'] != config.cohort_tickets \
            or any(authoritative[key] != 0 for key in ('available', 'pendingConfirmation', 'sold', 'complimentary')):
        raise RuntimeError('Cohort failed: one unique reservation per configured ticket, zero sales and all other buyers rejected are required')
    return result


def execute(config, directory, run_id, report):
    inventory = runtime_inventory()
    check_resources(config, inventory)
    report['runtimeBefore'] = inventory
    report['k6Version'] = command(['docker', 'run', '--rm', '--network', 'none', K6_IMAGE, 'version'], timeout=180)
    if not report['k6Version'].startswith('k6 v2.4.0 '):
        raise RuntimeError('Pinned image did not report the expected k6 version')
    report['preparationStartedAt'] = utc()
    dataset = seed_dataset(config, run_id)
    write_json(directory / 'dataset.json', dataset)
    report['datasetSha256'] = hashlib.sha256((directory / 'dataset.json').read_bytes()).hexdigest()
    report['loadStartedAt'] = utc()
    name = f'ticketing-perf-{run_id}'
    arguments = docker_arguments(config, directory, inventory['network']) + ['--name', name, K6_IMAGE, 'run', '--include-system-env-vars', '--no-usage-report',
                 '--log-format', 'raw', '--console-output', '/results/cohort-attempts.jsonl', '/scripts/load.js']
    process = None
    wall_deadline = time.monotonic() + config.duration_seconds + 180
    try:
        with (directory / 'runner.log').open('w', encoding='utf-8') as log, (directory / 'resources.jsonl').open('w', encoding='utf-8') as samples:
            process = subprocess.Popen(arguments, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            ids = [item['id'] for item in inventory['containers'] if item['running']]
            while process.poll() is None:
                if time.monotonic() > wall_deadline:
                    raise RuntimeError('Generator exceeded its bounded wall-clock budget')
                try:
                    stats = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids, name], timeout=20)
                    samples.write(json.dumps({'at': utc(), 'containers': [json.loads(line) for line in stats.splitlines()]}) + '\n')
                except (RuntimeError, subprocess.TimeoutExpired) as error:
                    samples.write(json.dumps({'at': utc(), 'samplingError': str(error)}) + '\n')
                samples.flush()
                time.sleep(5)
            report['k6ExitCode'] = process.returncode
    finally:
        if process is not None and process.poll() is None:
            subprocess.run(['docker', 'stop', '--time', '10', name], capture_output=True, timeout=20)
            process.wait(timeout=20)
        inspected = subprocess.run(['docker', 'inspect', name], text=True, capture_output=True, timeout=20)
        if inspected.returncode == 0:
            item = json.loads(inspected.stdout)[0]
            report['generatorState'] = {key: item['State'].get(key) for key in ('Status', 'ExitCode', 'OOMKilled', 'Error', 'StartedAt', 'FinishedAt')}
            subprocess.run(['docker', 'rm', name], check=True, capture_output=True, timeout=20)
    report['loadCompletedAt'] = utc()
    summary_path = directory / 'summary.json'
    if summary_path.exists():
        summary = json.loads(summary_path.read_text(encoding='utf-8'))
        report['failedThresholds'] = [f'{metric}:{threshold}' for metric, value in summary.get('metrics', {}).items()
                                      for threshold, verdict in value.get('thresholds', {}).items() if not verdict.get('ok', False)]
        report['k6State'] = summary.get('state', {})
    else:
        raise RuntimeError('No k6 summary was produced; the benchmark is incomplete')
    report['audit'] = audit(config, dataset, directory)
    report['runtimeAfter'] = runtime_inventory()
    if report['k6ExitCode'] != 0 or report.get('failedThresholds') or report.get('generatorState', {}).get('OOMKilled'):
        raise RuntimeError('Benchmark completed with failed thresholds, a generator error or OOM; inspect the evidence')


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('action', choices=('plan', 'validate', 'run'), nargs='?', default='plan')
    result.add_argument('--profile', choices=('smoke', 'full'), default='smoke')
    result.add_argument('--scenario', choices=('read', 'distributed', 'mixed', 'hotseat', 'cohort'), default='mixed')
    for name in ('vus', 'duration-seconds', 'max-rps', 'capacity', 'generator-memory-mib'):
        result.add_argument('--' + name, type=int)
    result.add_argument('--events', type=int, default=1)
    result.add_argument('--cohort-tickets', type=int, default=1)
    result.add_argument('--seed', type=int, default=20261116)
    result.add_argument('--generator-cpus', type=float)
    return result


def main():
    args = parser().parse_args()
    config = configuration(args, os.environ)
    if args.action == 'plan':
        print(json.dumps({'configuration': asdict(config), 'image': K6_IMAGE, 'applicationRequestsSent': 0,
                          'cohortBypassesPacing': config.scenario == 'cohort'}, indent=2))
        return
    run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    directory = RESULTS / run_id
    directory.mkdir(parents=True)
    report = {'runId': run_id, 'status': 'incomplete', 'action': args.action, 'configuration': asdict(config), 'image': K6_IMAGE,
              'requestPacingEnabled': config.scenario != 'cohort', 'memoryStabilityAssessed': False,
              'startedAt': utc(), 'gitSha': command(['git', 'rev-parse', 'HEAD']),
              'gitDirty': bool(command(['git', 'status', '--porcelain'])),
              'scriptSha256': hashlib.sha256((SCRIPTS / 'load.js').read_bytes()).hexdigest(),
              'composeSha256': hashlib.sha256((ROOT / 'docker-compose.yml').read_bytes()).hexdigest(),
              'host': {'platform': platform.platform(), 'python': platform.python_version(), 'cpuCount': os.cpu_count()}}
    try:
        if args.action == 'validate':
            write_json(directory / 'dataset.json', {'runId': 'static-validation', 'events': [{'eventId': 'validation', 'capacity': config.capacity}],
                                                   'hotEvent': {'eventId': 'validation-hot', 'capacity': config.cohort_tickets}, 'cohortStartsAt': 0})
            output = command(docker_arguments(config, directory, 'none') + ['--rm', K6_IMAGE, 'inspect', '--include-system-env-vars', '/scripts/load.js'], timeout=180)
            inspected = json.loads(output)
            actual = inspected['scenarios']['workload']
            if actual['vus'] != config.vus or actual['exec'] != ('cohortAttempt' if config.scenario == 'cohort' else 'workload') \
                    or (config.scenario != 'cohort' and inspected_duration_seconds(actual['duration']) != config.duration_seconds):
                raise RuntimeError('k6 inspect did not receive the requested execution configuration')
            write_json(directory / 'inspect.json', inspected)
            report['status'] = 'configuration-validated-without-application-traffic'
        else:
            execute(config, directory, run_id, report)
            report['status'] = 'passed'
    except BaseException as error:
        report['status'] = 'failed' if isinstance(error, Exception) else 'interrupted'
        report['error'] = str(error)
        raise
    finally:
        report['completedAt'] = utc()
        write_json(directory / 'report.json', report)
        print(f'Evidence: {directory}', flush=True)


if __name__ == '__main__':
    main()
