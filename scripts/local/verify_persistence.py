"""Restart only this Compose project's emulators and verify isolated durable fixtures."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ['docker', 'compose', '-f', str(ROOT / 'docker-compose.yml')]


def run(arguments):
    result = subprocess.run(arguments, cwd=ROOT, capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise RuntimeError('Local persistence probe command failed: ' + arguments[0])
    return result.stdout.strip()


def aws(service, *arguments):
    endpoint = {'dynamodb': 'http://dynamodb:8000', 'sqs': 'http://elasticmq:9324'}[service]
    raw = run([*COMPOSE, 'run', '--rm', '--no-deps', '--entrypoint', 'aws', 'bootstrap',
               '--endpoint-url', endpoint, service, *arguments, '--output', 'json'])
    return json.loads(raw or '{}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/quality/persistence.json')
    args = parser.parse_args()
    identifier = uuid.uuid4().hex
    table = 'ticketing-persistence-' + identifier
    queue_url = None
    created_table = False
    key = json.dumps({'pk': {'S': identifier}})
    document = {'startedAt': datetime.now(timezone.utc).isoformat(),
                'commit': run(['git', 'rev-parse', 'HEAD']),
                'workingTreeDirty': bool(run(['git', 'status', '--porcelain'])),
                'environment': 'local-emulators', 'status': 'failed'}
    try:
        for service in ('dynamodb', 'elasticmq'):
            if not run([*COMPOSE, 'ps', '-q', service]):
                raise RuntimeError('Start this project before testing persistence')
        aws('dynamodb', 'create-table', '--table-name', table,
            '--attribute-definitions', 'AttributeName=pk,AttributeType=S',
            '--key-schema', 'AttributeName=pk,KeyType=HASH', '--billing-mode', 'PAY_PER_REQUEST')
        created_table = True
        aws('dynamodb', 'put-item', '--table-name', table, '--item', key)
        queue_url = aws('sqs', 'create-queue', '--queue-name', table)['QueueUrl']
        sent = aws('sqs', 'send-message', '--queue-url', queue_url, '--message-body', identifier)
        run([*COMPOSE, 'restart', 'dynamodb', 'elasticmq'])
        deadline = time.monotonic() + 90
        while True:
            try:
                saved = aws('dynamodb', 'get-item', '--table-name', table, '--key', key, '--consistent-read')
                received = aws('sqs', 'receive-message', '--queue-url', queue_url, '--wait-time-seconds', '1')
                break
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(1)
        if saved.get('Item', {}).get('pk', {}).get('S') != identifier:
            raise AssertionError('DynamoDB item did not survive restart')
        messages = received.get('Messages', [])
        if not any(item.get('Body') == identifier and item.get('MessageId') == sent['MessageId'] for item in messages):
            raise AssertionError('SQS message/identity did not survive restart')
        document.update(status='passed', dynamodbItemPreserved=True, sqsMessagePreserved=True,
                        scope='One isolated table/item and queue/message; no AWS compatibility claim')
    except Exception as error:
        document['error'] = str(error)
    finally:
        # Only resources created by this invocation, on fixed emulator endpoints.
        for service, arguments in [('sqs', ('delete-queue', '--queue-url', queue_url)) if queue_url else (None, ()),
                                   ('dynamodb', ('delete-table', '--table-name', table)) if created_table else (None, ())]:
            if service:
                try:
                    aws(service, *arguments)
                except Exception:
                    document['status'] = 'failed'
                    document.setdefault('cleanupFailures', []).append(service)
        document['finishedAt'] = datetime.now(timezone.utc).isoformat()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8')
    print('persistence: ' + document['status'])
    raise SystemExit(0 if document['status'] == 'passed' else 1)


if __name__ == '__main__':
    main()
