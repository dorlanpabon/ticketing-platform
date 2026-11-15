'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '../..');
const COLLECTION = path.join(ROOT, 'contracts/ticketing.postman_collection.json');
const VERSION = '6.2.3';
const ENDPOINTS = Object.freeze({ catalog: 'http://localhost:18080', orders: 'http://localhost:18081', inventory: 'http://localhost:18082' });
const ITEMS = Object.freeze(['01 Create event', '02 Wait for ACTIVE', '03 Reserve two tickets',
  '04 Wait for reservation', '05 Idempotent retry', '06 Confirm', '07 Wait for SOLD',
  '08 Deny another customer', '09 Complimentary ticket', '10 Wait for COMPLIMENTARY',
  '11 Verify inventory counts', '12 Reject different payload for same key']);

function requireCheck(condition, message) {
  if (!condition) throw new Error(message);
}

function validateCollection(collection) {
  requireCheck(collection.info?.schema === 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json', 'Unexpected collection schema');
  const variables = Object.fromEntries((collection.variable || []).map(v => [v.key, v.value]));
  for (const [key, value] of Object.entries(ENDPOINTS)) {
    requireCheck(variables[key] === value, 'Only the fixed local Compose endpoints are supported');
  }
  requireCheck(JSON.stringify(collection.item?.map(item => item.name)) === JSON.stringify(ITEMS), 'Expected the complete ordered twelve-request collection');
  for (const item of collection.item) {
    requireCheck(typeof item.request?.url === 'string' && /^\{\{(catalog|orders|inventory)\}\}\/api\/v1\/[a-zA-Z0-9/{}-]+$/.test(item.request.url), 'Unexpected request destination');
    requireCheck(['GET', 'POST'].includes(item.request.method), 'Unexpected request method');
    const auth = item.request.header?.find(header => header.key.toLowerCase() === 'authorization');
    requireCheck(auth && /^Bearer local-(customer-[12]|admin)$/.test(auth.value), 'Only synthetic local identities are supported');
    requireCheck(item.event?.some(event => event.listen === 'test' && event.script?.exec?.length), 'Every request requires assertions');
  }
  return collection;
}

function errorClass(error) {
  const known = new Set(['Error', 'AssertionError', 'TypeError', 'SyntaxError', 'RangeError', 'RequestError', 'JSONError']);
  return error ? (known.has(error.name) ? error.name : 'Error') : null;
}

function number(value) {
  return Number.isFinite(value) && value >= 0 ? value : null;
}

function sanitizedSummary(error, summary) {
  const run = summary?.run || {};
  const executions = (run.executions || []).map(execution => ({
    item: ITEMS.includes(execution.item?.name) ? execution.item.name : 'Unrecognized item',
    statusCode: number(execution.response?.code),
    responseTimeMs: number(execution.response?.responseTime),
    assertions: (execution.assertions || []).length,
    failedAssertions: (execution.assertions || []).filter(assertion => assertion.error || assertion.skipped).length,
    errorClass: errorClass(execution.requestError)
  }));
  const failures = (run.failures || []).map(failure => ({
    item: ITEMS.includes(failure.source?.name) ? failure.source.name : 'Run',
    category: ['assertion', 'test', 'prerequest', 'request'].includes(failure.at) ? failure.at : 'run',
    errorClass: errorClass(failure.error)
  }));
  const stats = {};
  for (const key of ['requests', 'assertions', 'iterations', 'testScripts', 'prerequestScripts']) {
    stats[key] = Object.fromEntries(['total', 'failed', 'pending'].map(field => [field, number(run.stats?.[key]?.[field])]));
  }
  const seen = new Set(executions.map(execution => execution.item));
  const complete = ITEMS.every(name => seen.has(name));
  const valid = complete && executions.length >= ITEMS.length && executions.every(execution =>
    execution.statusCode !== null && execution.assertions > 0 && execution.failedAssertions === 0 && !execution.errorClass);
  const failed = error || summary?.error || run.error || failures.length || !valid ||
    stats.iterations.total !== 1 || stats.requests.failed !== 0 || stats.assertions.failed !== 0 ||
    stats.assertions.pending !== 0 || stats.assertions.total < ITEMS.length;
  return { status: failed ? 'failed' : 'passed', allRequestsExecuted: complete,
    errorClass: errorClass(error || summary?.error || run.error), stats, executions, failures };
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function command(binary, args) {
  return execFileSync(binary, args, { cwd: ROOT, encoding: 'utf8', timeout: 30000, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

function runtimeImages() {
  const output = command('docker', ['compose', 'ps', '--format', 'json']);
  const containers = output.startsWith('[') ? JSON.parse(output) : output.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
  const services = ['catalog', 'orders', 'inventory', 'catalog-worker', 'orders-worker', 'inventory-worker'];
  const result = [];
  for (const service of services) {
    const instances = containers.filter(container => container.Service === service);
    requireCheck(instances.length >= (service === 'inventory-worker' ? 2 : 1), 'Required API/worker replicas are missing');
    for (const container of instances) {
      requireCheck(container.State === 'running' && container.Health === 'healthy', 'All API/worker containers must be healthy');
      const image = command('docker', ['inspect', '--format', '{{.Image}}', container.ID]);
      requireCheck(/^sha256:[a-f0-9]{64}$/.test(image), 'Container image identity is missing');
      result.push({ service, containerId: container.ID, imageId: image });
    }
  }
  return result;
}

function identifiers(summary) {
  const values = {};
  const put = (key, value) => {
    if (typeof value === 'string' && /^[a-f0-9-]{36}$/i.test(value)) values[key] = value;
  };
  for (const execution of summary?.run?.executions || []) {
    try {
      const body = JSON.parse(execution.response?.stream?.toString() || '{}');
      if (execution.item?.name === ITEMS[0]) {
        put('eventId', body.eventId);
        const key = execution.request?.headers?.get('Idempotency-Key');
        if (typeof key === 'string' && key.startsWith('event-')) put('runId', key.slice(6));
      }
      if (execution.item?.name === ITEMS[2]) put('orderId', body.orderId);
      if (execution.item?.name === ITEMS[8]) put('courtesyId', body.orderId);
    } catch { /* Malformed responses do not add identifiers or expose their bodies. */ }
  }
  return values;
}

function resolveTooling(modulePath) {
  requireCheck(typeof modulePath === 'string' && path.isAbsolute(modulePath), 'NEWMAN_MODULE must name an explicit external installation');
  const moduleRoot = fs.realpathSync(modulePath);
  const relative = path.relative(ROOT, moduleRoot);
  requireCheck(relative.startsWith('..' + path.sep) || path.isAbsolute(relative), 'Newman tooling must remain outside the application repository');
  const metadata = JSON.parse(fs.readFileSync(path.join(moduleRoot, 'package.json'), 'utf8'));
  requireCheck(metadata.name === 'newman' && metadata.version === VERSION, 'Use the audited Newman 6.2.3 installation');
  const lock = path.resolve(moduleRoot, '../../package-lock.json');
  requireCheck(fs.existsSync(lock), 'External tooling lockfile must be retained');
  return { moduleRoot, lock };
}

async function execute() {
  requireCheck(/^v24\./.test(process.version), 'Use Node.js 24 LTS');
  const tooling = resolveTooling(process.env.NEWMAN_MODULE);
  const collection = validateCollection(JSON.parse(fs.readFileSync(COLLECTION, 'utf8')));
  const directory = path.join(ROOT, 'evidence/local/postman', new Date().toISOString().replace(/[:.]/g, '-') + '-' + crypto.randomUUID());
  fs.mkdirSync(directory, { recursive: true });
  const report = { schemaVersion: 1, status: 'running', environment: 'local-emulators',
    candidateEvidence: false, startedAt: new Date().toISOString(), nodeVersion: process.version, newmanVersion: VERSION,
    collectionSha256: sha256(COLLECTION), runnerSha256: sha256(__filename),
    lockSha256: sha256(tooling.lock), endpoints: ENDPOINTS,
    dependencyAuditStatus: 'failed-known-upstream-vulnerabilities',
    dependencyAdvisory: 'GHSA-qxc2-j82w-r537; see scripts/postman/README.md and the retained external npm audit',
    limits: { iterations: 1, delayRequestMs: 500, timeoutMs: 360000, timeoutRequestMs: 15000, timeoutScriptMs: 2000 },
    reportPolicy: 'Allowlisted counters, opaque IDs and error classes only; no headers, bodies, variable exports, raw errors or credentials.' };
  const output = path.join(directory, 'report.json');
  const save = () => fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n', { encoding: 'utf8', mode: 0o600 });
  save();
  try {
    report.commit = command('git', ['rev-parse', 'HEAD']);
    report.tree = command('git', ['rev-parse', 'HEAD^{tree}']);
    report.workingTreeDirty = Boolean(command('git', ['status', '--porcelain']));
    report.images = runtimeImages();
    for (const key of ['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'http_proxy', 'https_proxy', 'all_proxy']) delete process.env[key];
    process.env.NO_PROXY = process.env.no_proxy = 'localhost,127.0.0.1';
    const result = await new Promise(resolve => require(tooling.moduleRoot).run({ collection, reporters: [],
      iterationCount: 1, delayRequest: 500, timeout: 360000, timeoutRequest: 15000, timeoutScript: 2000,
      bail: { failure: true }, ignoreRedirects: true, insecure: false,
      workingDir: path.dirname(COLLECTION), insecureFileRead: false
    }, (error, summary) => resolve({ error, summary })));
    Object.assign(report, sanitizedSummary(result.error, result.summary));
    report.identifiers = identifiers(result.summary);
  } catch (error) {
    report.errorClass = errorClass(error);
    report.status = 'failed';
  }
  report.finishedAt = new Date().toISOString();
  save();
  console.log(JSON.stringify({ status: report.status, evidence: path.relative(ROOT, output),
    requests: report.stats?.requests?.total || 0, assertions: report.stats?.assertions?.total || 0 }));
  return report.status === 'passed' ? 0 : 1;
}

async function main() {
  const action = process.argv[2] || 'validate';
  requireCheck(process.argv.length <= 3 && ['validate', 'run'].includes(action), 'Usage: node scripts/postman/run.cjs [validate|run]');
  if (action === 'validate') {
    validateCollection(JSON.parse(fs.readFileSync(COLLECTION, 'utf8')));
    console.log(JSON.stringify({ status: 'configuration-validated-without-http-requests', items: ITEMS.length, newmanVersion: VERSION }));
    return 0;
  }
  return execute();
}

module.exports = { validateCollection, sanitizedSummary, errorClass, identifiers, resolveTooling, ITEMS, ENDPOINTS };
if (require.main === module) main().then(code => { process.exitCode = code; }).catch(error => {
  console.error(JSON.stringify({ status: 'failed-before-execution', errorClass: errorClass(error) }));
  process.exitCode = 1;
});
