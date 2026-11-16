// Exercise the actual k6 request construction without HTTP or containers.
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = readFileSync(join(__dirname, 'load.js'), 'utf8')
  .replace(/^import .*;\r?\n/gm, '').replace(/^export /gm, '');

function runtime(vus, capacity) {
  const requests = [];
  const attempts = [];
  const context = vm.createContext({
    __ENV: { SCENARIO: 'cohort', VUS: String(vus), PERF_STRESS: '1' },
    open: () => JSON.stringify({ runId: 'test', hotEvent: { eventId: 'event', capacity }, cohortStartsAt: 0 }),
    exec: { vu: { idInTest: 1 } },
    Counter: class { add() {} }, Rate: class { add() {} }, Trend: class { add() {} },
    sleep: () => { throw new Error('Synchronized cohort must not use request pacing'); },
    console: { log: line => attempts.push(JSON.parse(line)) },
    http: {
      expectedStatuses: () => null,
      request: (method, url, body, options) => {
        requests.push({ method, url, body: JSON.parse(body), options });
        return { status: 202, json: () => ({ orderId: 'order-' + requests.length }) };
      },
    },
  });
  vm.runInContext(source, context);
  return { context, requests, attempts };
}

test('1000 buyers request 100 numbered seats fairly with unique idempotency keys', () => {
  const { context, requests, attempts } = runtime(1000, 100);
  for (let vu = 1; vu <= 1000; vu++) {
    context.exec.vu.idInTest = vu;
    context.cohortAttempt();
  }
  const counts = new Map();
  for (let index = 0; index < requests.length; index++) {
    const { method, body, options } = requests[index];
    assert.equal(method, 'POST');
    assert.equal(body.eventId, 'event');
    assert.equal(body.ticketIds.length, 1);
    const ticket = body.ticketIds[0];
    counts.set(ticket, (counts.get(ticket) || 0) + 1);
    assert.equal(attempts[index].ticketId, ticket);
    assert.equal(attempts[index].vu, index + 1);
    assert.equal(options.headers['Idempotency-Key'], `test-cohort-${index + 1}`);
  }
  assert.equal(counts.size, 100);
  assert.deepEqual([...counts.values()], Array(100).fill(10));
  assert.equal(attempts.length, 1000);
});

test('default single-seat dataset keeps all 100 buyers on ticket 1', () => {
  const { context, requests } = runtime(100, 1);
  for (let vu = 1; vu <= 100; vu++) {
    context.exec.vu.idInTest = vu;
    context.cohortAttempt();
  }
  assert.equal(requests.length, 100);
  assert.ok(requests.every(request => request.body.ticketIds[0] === '1'));
});

test('invalid cohort capacity is rejected before requests', () => {
  for (const capacity of [0, -1, 101, 1.5, '100']) {
    assert.throws(() => runtime(100, capacity), /Cohort capacity/);
  }
});
