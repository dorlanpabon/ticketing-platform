# Postman collection verification

The delivered [collection](../../contracts/ticketing.postman_collection.json) runs twelve stages against the local Compose APIs: create/activate event, reserve, retry idempotently, confirm/SOLD, deny another customer, issue courtesy/COMPLIMENTARY, verify counters and reject a conflicting payload. Each complete iteration replaces `runId` and clears old IDs/poll counters. Run the whole collection from its first request; isolated requests may lack required variables.

Use Node.js 24 LTS. Newman **6.2.3** was verified against the official npm registry metadata (`repository: postmanlabs/newman`, Node >=16) on 2026-11-16. Its installed official README documents the programmatic API, timing options, summaries and reporters. References: [official package](https://www.npmjs.com/package/newman/v/6.2.3), [official source/API README](https://github.com/postmanlabs/newman#api-reference).

**Newman is an optional external development tool, not an application dependency or CI promotion gate.** The official dependency graph failed npm audit: 19 affected packages (12 high, one critical, six moderate) at the recorded review. Its manifest, lockfile, installation and original audit were retained in an external tooling directory. No vulnerable package-lock, node_modules or audit suppression is included in the application repository. The collection remains independently importable in the Postman GUI; the primary acceptance suite is Python.

The blocking compatibility finding is [GHSA-qxc2-j82w-r537](https://github.com/advisories/GHSA-qxc2-j82w-r537): `@faker-js/faker` helpers.fake can execute code, affecting versions through 10.4.0. Newman pins a Collection SDK that consumes Faker 5.5.3's legacy exports/APIs; current SDK 5.3.1 still pins that version. The fixed Faker 10.6.0 has incompatible exports/namespaces, so a forced major override was not applied. This collection uses `{{$guid}}` through `uuid.v4`, not helpers.fake, but that restricted usage does **not** make the dependency audit pass. The original graph also reports Handlebars [GHSA-2w6w-674q-4c4q](https://github.com/advisories/GHSA-2w6w-674q-4c4q) and other transitive findings. Do not run arbitrary collections/scripts or treat this tool as an approved general runner. Reassess an upstream compatible fix before broader use.

```powershell
node --test scripts/postman/run.test.cjs
node scripts/postman/run.cjs validate
```

These commands need no Newman installation and send **no application HTTP requests**. Unit tests use synthetic Newman summary objects to verify redaction/fail-closed behavior; they are not evidence that the API or Newman collection passed.

After any expiration/chaos/load test finishes and the environment is restored, explicitly run:

```powershell
docker compose up -d --wait --scale inventory-worker=2
$env:NEWMAN_MODULE = (Resolve-Path '../postman-tooling/node_modules/newman').Path
node scripts/postman/run.cjs run
```

The example assumes the explicitly reviewed external installation already exists. A fresh clone does not install or download Newman automatically. `NEWMAN_MODULE` must be an absolute path outside this repository, with version 6.2.3 and its external lockfile retained; missing/mismatched tooling fails closed. Installation/audit decisions remain separate from running application code.

Execution creates a fresh twenty-seat event, purchases two tickets and issues one courtesy; it does not remove existing data. The runner requires healthy APIs, workers and two Inventory worker replicas. Fixed localhost ports and synthetic local identities are intentional; it does not accept AWS targets, external environments or proxy routing. Redirects are disabled. Collection scripts are trusted repository code, not an arbitrary untrusted-script sandbox.

Requests are spaced by 500 ms; each request is limited to 15 s, script to 2 s, complete run to six minutes. Four asynchronous polling stages have at most 120 attempts each. Tests fail if a terminal condition is not observed; polling is not implemented with blocking sleeps inside scripts. The Node process exits nonzero for incomplete runs, skipped/failed assertions, transport failures or missing stages. The default action is `validate`, never traffic generation.

Each real run writes `evidence/local/postman/<UTC>-<uuid>/report.json`, ignored by Git. It records actual Newman request/assertion counts, per-execution HTTP code/time, failure classes, completed stages, opaque dataset IDs, Git SHA/tree/dirty state, collection/runner/lock hashes and running container image IDs. The report deliberately does not serialize raw Newman summaries, request/response bodies, headers, cookies, environment exports, error messages or credentials. Do not add the standard JSON reporter without a reviewed redaction policy. Failures remain recorded; do not edit their status.

`status: passed` means the collection completed with its assertions passing in that local runtime. It does not approve the dependency graph: `dependencyAuditStatus` records its failure separately. It does not prove a clean candidate SHA merely because HEAD was recorded, and `candidateEvidence` is always false. The collection does not wait ten minutes or exercise chaos/concurrency: [acceptance verify.py](../acceptance/verify.py) remains responsible for the mandatory expiry and recovery gates. Response times here are smoke observations, not p95 benchmarks.

The first real execution completed all twelve stages with 33 HTTP requests and 41 passing assertions. Its unchanged report is `evidence/local/postman/2026-11-16T09-54-08-653Z-c7189e49-908a-4955-971b-b3edf5f82662/report.json`; it used a dirty development tree and the graph whose audit failed. This historical functional result is not rewritten as a clean security or candidate result. Later runs have their own report/hashes. An interrupted run may retain `status: running`; it must not be counted as passed.

The final external-tool runner was also executed after the expiration and emulator-restart probes finished: **34 requests, 42 assertions, twelve stages, zero failures**, between 10:08:01 and 10:08:24 UTC on 2026-11-16. Its [report](../../evidence/local/postman/2026-11-16T10-08-01-052Z-58c1810b-0317-4e4a-9232-5829e6d7eda6/report.json) records fresh run/event/order/courtesy IDs, HEAD `6debcf20f1e52b6f2f9c36008c6682a2b9fc953b`, `workingTreeDirty=true`, `candidateEvidence=false` and `dependencyAuditStatus=failed-known-upstream-vulnerabilities`. This is a functional collection pass with an explicitly failed tooling dependency audit, not a security approval.
