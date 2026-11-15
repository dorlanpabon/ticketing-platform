import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const dataset = JSON.parse(open(__ENV.DATASET_PATH || '/results/dataset.json'));
const scenario = __ENV.SCENARIO || 'mixed';
const vus = Number(__ENV.VUS || 50);
const duration = __ENV.DURATION || '30s';
const maxRps = Number(__ENV.MAX_RPS || 20);
const seed = Number(__ENV.SEED || 20261116);
const pollSeconds = Number(__ENV.POLL_TIMEOUT_SECONDS || 90);
const cohort = scenario === 'cohort';
if (!['read', 'distributed', 'mixed', 'hotseat', 'cohort'].includes(scenario)) throw new Error('Unknown scenario');
const endpoints = { catalog: 'http://catalog:8080', orders: 'http://orders:8080', inventory: 'http://inventory:8080' };

if (vus > 50 && __ENV.PERF_FULL !== '1' && __ENV.PERF_STRESS !== '1') {
  throw new Error('More than 50 VUs requires an explicit full/stress opt-in');
}
if (cohort && __ENV.PERF_STRESS !== '1') throw new Error('Cohort requires PERF_STRESS=1');
if (vus < 1 || vus > 1000 || maxRps < 1 || maxRps > 1000) throw new Error('Invalid workload bounds');
if (!/^\d+s$/.test(duration) || Number(duration.slice(0, -1)) < 1 || Number(duration.slice(0, -1)) > 1800) {
  throw new Error('Duration must be 1..1800 seconds');
}
if (!cohort && __ENV.PERF_FULL !== '1' && (vus > 50 || maxRps > 20 || Number(duration.slice(0, -1)) > 30)) {
  throw new Error('Smoke bounds exceeded without PERF_FULL=1');
}

const technicalErrors = new Rate('technical_errors');
const contractErrors = new Counter('response_contract_errors');
const invariantErrors = new Rate('inventory_invariant_errors');
const accepted = new Counter('purchase_accepted');
const rejected = new Counter('business_rejections');
const unresolved = new Counter('async_unresolved');
const exhausted = new Counter('dataset_exhausted');
const sold = new Counter('purchase_sold');
const hotSold = new Counter('hot_seat_sold');
const confirmation = new Trend('confirmation_observed_ms', true);

const thresholds = {
  http_reqs: ['count>0'],
  iterations: ['count>0'],
  technical_errors: ['rate<0.01'],
  response_contract_errors: ['count==0'],
  dataset_exhausted: ['count==0'],
  async_unresolved: ['count==0'],
};
if (scenario === 'read' || scenario === 'mixed') {
  thresholds['http_req_duration{operation:availability}'] = ['p(95)<300'];
  thresholds.inventory_invariant_errors = ['rate==0'];
}
if (scenario !== 'read') {
  thresholds.purchase_accepted = ['count>0'];
  thresholds['http_req_duration{operation:order_create}'] = ['p(95)<500'];
}
if (!cohort && scenario !== 'read') {
  thresholds.purchase_sold = ['count>0'];
  thresholds.confirmation_observed_ms = ['p(95)<5000'];
}
if (scenario === 'hotseat') thresholds.hot_seat_sold = ['count==1'];

export const options = {
  scenarios: {
    workload: cohort
      ? { executor: 'per-vu-iterations', vus, iterations: 1, maxDuration: '120s', exec: 'cohortAttempt' }
      : { executor: 'constant-vus', vus, duration, gracefulStop: '30s', exec: 'workload' },
  },
  thresholds,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  discardResponseBodies: false,
  userAgent: 'ticketing-performance/1.0',
};

let nextRequestAt = 0;
let purchaseSequence = 0;
let initialized = false;

function initializeMetrics() {
  if (initialized) return;
  initialized = true;
  contractErrors.add(0);
  accepted.add(0);
  rejected.add(0);
  unresolved.add(0);
  exhausted.add(0);
  sold.add(0);
  hotSold.add(0);
}

function pace() {
  if (cohort) return;
  const interval = vus * 1000 / maxRps;
  if (!nextRequestAt) nextRequestAt = Date.now() + (exec.vu.idInTest - 1) * interval / vus;
  const wait = nextRequestAt - Date.now();
  if (wait > 0) sleep(wait / 1000);
  nextRequestAt = Math.max(nextRequestAt, Date.now()) + interval;
}

function request(service, path, operation, method = 'GET', body = null, key = null) {
  pace();
  const customer = exec.vu.idInTest % 2 ? 'local-customer-1' : 'local-customer-2';
  const headers = { Authorization: `Bearer ${customer}`, 'Content-Type': 'application/json' };
  if (key) headers['Idempotency-Key'] = key;
  const response = http.request(method, endpoints[service] + path, body === null ? null : JSON.stringify(body), {
    headers, timeout: '10s', redirects: 0, tags: { name: operation, operation },
    responseCallback: http.expectedStatuses(200, 202, 409, 410),
  });
  const businessConflict = (operation === 'order_create' || operation === 'order_confirm') && [409, 410].includes(response.status);
  technicalErrors.add(response.status !== (method === 'GET' ? 200 : 202) && !businessConflict, { operation });
  if (businessConflict) rejected.add(1, { phase: operation, status: String(response.status) });
  return response;
}

function json(response) {
  try { return response.json(); } catch (_) { contractErrors.add(1, { operation: 'invalid_json' }); return null; }
}

function invariants(snapshot) {
  const fields = ['available', 'reserved', 'pendingConfirmation', 'sold', 'complimentary'];
  return snapshot && Number.isInteger(snapshot.capacity) && fields.every(key => Number.isInteger(snapshot[key]) && snapshot[key] >= 0)
    && fields.reduce((sum, key) => sum + snapshot[key], 0) === snapshot.capacity;
}

function read() {
  const event = dataset.events[(exec.vu.idInTest + exec.vu.iterationInScenario + seed) % dataset.events.length];
  const choice = (exec.vu.iterationInScenario + exec.vu.idInTest + seed) % 3;
  if (choice === 0) {
    const response = request('inventory', `/api/v1/events/${event.eventId}/availability`, 'availability');
    if (response.status === 200) invariantErrors.add(!invariants(json(response)));
  } else if (choice === 1) {
    request('inventory', `/api/v1/events/${event.eventId}/tickets?limit=25`, 'tickets_page');
  } else {
    request('catalog', `/api/v1/events/${event.eventId}`, 'event_get');
  }
}

function selection(hot) {
  if (hot) return { eventId: dataset.hotEvent.eventId, ticketId: '1' };
  const total = dataset.events.reduce((count, event) => count + event.capacity, 0);
  const ordinal = purchaseSequence++ * vus + exec.vu.idInTest - 1;
  if (ordinal >= total) {
    exhausted.add(1);
    exec.test.abort('Unique-ticket dataset exhausted; increase capacity or lower the arrival budget');
  }
  let offset = (ordinal + seed % total) % total;
  for (const event of dataset.events) {
    if (offset < event.capacity) return { eventId: event.eventId, ticketId: String(offset + 1) };
    offset -= event.capacity;
  }
  throw new Error('Invalid dataset capacity');
}

function waitForOrder(order, predicate, startedAt) {
  let current = order;
  while (Date.now() - startedAt < pollSeconds * 1000) {
    if (predicate(current)) return current;
    sleep(0.25);
    const response = request('orders', `/api/v1/orders/${order.orderId}`, 'order_get');
    if (response.status !== 200) { unresolved.add(1); return null; }
    current = json(response);
    if (!current) { unresolved.add(1); return null; }
  }
  unresolved.add(1);
  return null;
}

function purchase(hot) {
  const selected = selection(hot);
  const key = `${dataset.runId}-${exec.vu.idInTest}-${exec.vu.iterationInScenario}`;
  const response = request('orders', '/api/v1/orders', 'order_create', 'POST',
    { eventId: selected.eventId, ticketIds: [selected.ticketId] }, key);
  if (response.status !== 202) return;
  accepted.add(1);
  let order = json(response);
  if (!order || !order.orderId) { contractErrors.add(1, { operation: 'missing_order_id' }); return; }
  order = waitForOrder(order, value => value.workflowStatus !== 'RESERVING', Date.now());
  if (!order) return;
  if (['REJECTED', 'EXPIRED'].includes(order.workflowStatus)) { rejected.add(1, { phase: 'asynchronous' }); return; }
  const confirm = request('orders', `/api/v1/orders/${order.orderId}/confirm`, 'order_confirm', 'POST', null, `${key}-confirm`);
  if (confirm.status !== 202) return;
  const startedAt = Date.now();
  order = waitForOrder(order, value => ['COMPLETED', 'REJECTED', 'EXPIRED'].includes(value.workflowStatus), startedAt);
  if (order && order.status === 'SOLD') {
    confirmation.add(Date.now() - startedAt);
    sold.add(1);
    if (hot) hotSold.add(1);
  } else if (order) rejected.add(1, { phase: 'confirmation' });
}

export function workload() {
  initializeMetrics();
  if (scenario === 'read') return read();
  if (scenario === 'distributed') return purchase(false);
  if (scenario === 'hotseat') return purchase(true);
  const choice = (Math.imul(exec.vu.idInTest, 1103515245) + Math.imul(exec.vu.iterationInScenario, 12345) + seed) >>> 0;
  if (choice % 100 < 80) return read();
  return purchase(choice % 100 >= 95);
}

export function cohortAttempt() {
  initializeMetrics();
  const wait = dataset.cohortStartsAt - Date.now();
  if (wait > 0) sleep(wait / 1000);
  const sentAt = Date.now();
  const response = request('orders', '/api/v1/orders', 'order_create', 'POST',
    { eventId: dataset.hotEvent.eventId, ticketIds: ['1'] }, `${dataset.runId}-cohort-${exec.vu.idInTest}`);
  const body = response.status === 202 ? json(response) : null;
  if (response.status === 202) accepted.add(1);
  console.log(JSON.stringify({ kind: 'cohort_attempt', vu: exec.vu.idInTest, sentAt, status: response.status, orderId: body && body.orderId }));
}

export function handleSummary(data) {
  return { '/results/summary.json': JSON.stringify(data, null, 2) + '\n' };
}
