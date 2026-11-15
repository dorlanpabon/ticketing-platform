'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { validateCollection, sanitizedSummary, identifiers, resolveTooling, ITEMS } = require('./run.cjs');
const load = () => JSON.parse(fs.readFileSync(path.join(__dirname, '../../contracts/ticketing.postman_collection.json'), 'utf8'));

test('rejects external destinations and missing collection stages without HTTP', () => {
  assert.equal(validateCollection(load()).item.length, 12);
  const remote = load(); remote.variable[0].value = 'https://example.com';
  assert.throws(() => validateCollection(remote));
  const changed = load(); changed.item[0].request.url = 'https://example.com/api/v1/events';
  assert.throws(() => validateCollection(changed));
  const incomplete = load(); incomplete.item.pop();
  assert.throws(() => validateCollection(incomplete));
});

test('first request always replaces a persisted runId and clears stale state', () => {
  const values = new Map([['runId', 'old-run'], ['orderId', 'old-order']]);
  const local = new Map([['poll_02 Wait for ACTIVE', 119]]);
  let generated = 0;
  const context = { pm: {
    info: { requestName: ITEMS[0] },
    collectionVariables: { get: key => values.get(key), set: (key, value) => values.set(key, value), unset: key => values.delete(key) },
    variables: { replaceIn: () => `run-${++generated}`, unset: key => local.delete(key) }
  } };
  const script = load().event.find(event => event.listen === 'prerequest').script.exec.join('\n');
  vm.runInNewContext(script, context);
  assert.equal(values.get('runId'), 'run-1');
  assert.equal(values.has('orderId'), false);
  assert.equal(local.size, 0);
  const date = values.get('futureDate');
  context.pm.info.requestName = ITEMS[4];
  vm.runInNewContext(script, context);
  assert.equal(values.get('runId'), 'run-1');
  assert.equal(values.get('futureDate'), date);
  context.pm.info.requestName = ITEMS[0];
  vm.runInNewContext(script, context);
  assert.equal(values.get('runId'), 'run-2');
});

function summary() {
  return { run: { failures: [], stats: {
    requests: { total: 12, failed: 0, pending: 0 },
    assertions: { total: 12, failed: 0, pending: 0 },
    iterations: { total: 1, failed: 0, pending: 0 }
  }, executions: ITEMS.map(name => ({ item: { name }, response: { code: 200, responseTime: 2 }, assertions: [{}] })) } };
}

test('only a completed collection with passing real Newman counters may pass', () => {
  assert.equal(sanitizedSummary(null, summary()).status, 'passed');
  assert.equal(sanitizedSummary(null, {}).status, 'failed');
  const partial = summary(); partial.run.executions.pop();
  assert.equal(sanitizedSummary(null, partial).status, 'failed');
  const skipped = summary(); skipped.run.executions[0].assertions[0].skipped = true;
  assert.equal(sanitizedSummary(null, skipped).status, 'failed');
  assert.equal(sanitizedSummary(new Error('connection failed'), summary()).status, 'failed');
});

test('report excludes tokens, raw bodies, error messages and arbitrary variables', () => {
  const value = summary();
  const secret = 'NEVER_WRITE_THIS_TOKEN';
  value.environment = { token: secret };
  value.run.executions[0].request = { header: { Authorization: secret }, body: secret };
  value.run.executions[0].response.stream = secret;
  value.run.failures = [{ at: secret, source: { name: secret }, error: { name: secret, message: secret, stack: secret } }];
  const sanitized = sanitizedSummary(null, value);
  assert.equal(sanitized.status, 'failed');
  assert.equal(JSON.stringify(sanitized).includes(secret), false);
  assert.deepEqual(identifiers({ collection: { variables: { get: () => secret } } }), {});
});

test('requires external tooling explicitly and extracts only opaque response identifiers', () => {
  assert.throws(() => resolveTooling(undefined));
  assert.throws(() => resolveTooling(__dirname));
  const id = '4b72051a-bd0f-4f14-967a-ab9fb3ba28b7';
  const execution = { item: { name: ITEMS[0] }, request: { headers: { get: () => 'event-' + id } },
    response: { stream: Buffer.from(JSON.stringify({ eventId: id, customerId: 'PRIVATE' })) } };
  assert.deepEqual(identifiers({ run: { executions: [execution] } }), { eventId: id, runId: id });
});
