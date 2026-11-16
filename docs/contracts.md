# Implemented HTTP and messaging contracts

The machine-readable contracts are [OpenAPI 3.1](../contracts/openapi.json) and [AsyncAPI 3.0](../contracts/asyncapi.json). This document describes the implementation and its limits. A schema-valid document does not, by itself, prove runtime correctness, AWS deployment or production capacity.

## Requirement, decision and implementation

The source is **Prueba 2026 desarrolladores (2).pdf**, pages 2–6. Its requirements are distinct from the later architecture choices and user preferences.

| PDF requirement | Implemented contract | Interpretation or limitation |
| --- | --- | --- |
| Event creation/query with name, date, venue and capacity, p. 3 | Catalog `POST /api/v1/events`, `GET /api/v1/events`, `GET /api/v1/events/{eventId}` | Creation returns `PROVISIONING`; the list contains `ACTIVE` events after asynchronous inventory readiness. Numeric limits and admin authorization are implementation choices. |
| Temporary reservation, maximum ten minutes, p. 3 | Orders fixes `requestedAt` and `expiresAt` when it stores the order. Inventory owns the allocation. | Retries, confirmation, recovery and delayed publication keep the initial deadline. |
| Immediately enqueue purchase and return an order ID, p. 3 | Orders commits the order and command outbox atomically. The API or relay claims and publishes to SQS. | `202` follows confirmation of enqueue. Inventory processes the command asynchronously. |
| Query an order using the five specified states, p. 3 | Owner-only `GET /api/v1/orders/{orderId}` with `status`, `workflowStatus`, `resolution`, `inventoryVersion` | `workflowStatus` supplements the five ticket states. An unresolved initial order can remain `RESERVING` until Inventory produces a durable result. |
| No overselling under concurrency, p. 4 | Conditional Inventory transactions for all selected tickets, counters, reservation, audit and outbox | Transactions stay inside the owning service. A successful HTTP acceptance alone does not prove seat ownership. |
| Periodically identify and release expired reservations, p. 4 | `SweepExpiredReservations` every minute, consumed under a durable global lease | Per-reservation delayed SQS messages are the primary timer; the periodic sweep still exists as required recovery. There is no per-reservation AWS Scheduler resource or Lambda. |
| Real-time availability including sales and reservations, p. 4 | Inventory `GET /api/v1/events/{eventId}/availability` reads counters in one transaction | This is a snapshot of persisted state. Release materialization may lag: up to five seconds is conservatively counted as held; greater detected lag returns `503 INVENTORY_STALE`. Five seconds is a plan/implementation policy, not a numeric SLA in the PDF. |
| `SOLD` final and irreversible; `COMPLIMENTARY` final and non-accounting, p. 2 | Neither final state has an outgoing transition; only `sold` counts sales | No payments, prices, refunds, monetary accounting or manual cancellation API is implemented. The PDF does not require manual cancellation. |
| Reactive API, conditional writes and at-least-once SQS, pp. 4–5 | WebFlux, DynamoDB async conditional transactions and SQS Standard | At-least-once delivery allows duplicates and reordering. Idempotent effects do not turn delivery into exactly once. |

Three separate service owners, explicit confirmation, numbered ticket selection and the maximum of ten selected tickets per order came from the design/user decisions. The PDF does not prescribe those exact boundaries or numeric request limits. Capacity is limited by this implementation to 100,000 tickets per event. Ticket IDs are canonical decimal strings from `"1"` through `"100000"`, without leading zeroes; a requested ID must also exist in the event.

## HTTP addresses and access

| Owner | Local base URL | Routes |
| --- | --- | --- |
| Catalog | `http://localhost:18080` | `/api/v1/events`, `/api/v1/events/{eventId}` |
| Orders | `http://localhost:18081` | `/api/v1/orders`, `/api/v1/orders/{orderId}`, `/api/v1/orders/{orderId}/confirm`, `/api/v1/admin/complimentary-orders` |
| Inventory | `http://localhost:18082` | `/api/v1/events/{eventId}/tickets`, `/api/v1/events/{eventId}/availability` |
| Private Inventory | `http://inventory:8080` inside Compose; loopback port 18082 for local inspection | `PUT/GET /internal/reservations/{orderId}`, `POST /internal/reservations/{orderId}/confirm` |

OpenAPI specifies a server on each path so Orders and Inventory requests do not accidentally go to Catalog. For AWS, use the exported API Gateway URL for public routes. Internal routes have no API Gateway mapping and rely on private service network permissions. Their OpenAPI `security: []` reflects the absence of bearer authentication on that private hop, **not** authorization for public exposure.

Public operations require an authenticated identity. Creation of events and complimentary orders requires the exact `admin` group. Reading and confirming an order requires its beneficiary/customer identity; an administrator does not automatically gain access to another customer's order. An absent or foreign order returns 404.

AWS ingress validates JWTs and replaces the trusted identity headers. Local mode requires both `AUTH_MODE=local` and the local profile; only `Bearer local-customer-1`, `Bearer local-customer-2` and `Bearer local-admin` are accepted synthetic identities. Those values are local test fixtures, not production JWTs.

API deployments run with workers disabled. Worker deployments reject business HTTP routes and expose only the permitted health/metrics endpoints. Health and Prometheus are operational endpoints, not ticketing business operations in this OpenAPI document.

## Requests, responses and idempotency

- Event creation requires `name`, `venue`, a future `startsAt`, and `capacity`. An empty body now returns `400 INVALID_BODY`. Event creation and order creation return `202` with a relative `Location` header.
- A purchase contains `eventId` and 1–10 distinct `ticketIds`. The authenticated subject supplies the customer identity. HTTP idempotency compares a canonicalized ticket selection; reordering the same selection does not request a different allocation.
- A complimentary request also contains `beneficiaryId` and a nonblank `reason` of at most 500 characters. The actor is the authenticated administrator. It consumes capacity but never increments `sold`.
- Purchase and complimentary creation wait for successful SQS enqueue or evidence of a previous send before returning `202`. If Orders cannot confirm enqueue, it returns `503 ORDER_ENQUEUE_UNAVAILABLE`. The client retries with the same key and payload to recover the original order and deadline. The pending command remains available to the relay.
- Public confirmation has **no body**. It requires `Idempotency-Key`, persists a stable `ConfirmPurchase` command and returns `202` with the currently observed order. It does not emit a `Location` header or guarantee that the returned status is already `SOLD`.
- Public write keys match `[A-Za-z0-9_.:-]{8,128}`. Keys are scoped by owner/actor and operation. Reuse for another payload returns 409; successful replay returns the original durable result. A confirmation key cannot be rebound to another order.
- Private reservation uses the path `orderId` as the immutable idempotency key. Its normalized payload, including ticket order and deadline, must remain unchanged. The path wins if a body happens to contain `orderId`; Orders omits that body property. A past deadline or unavailable selection can return **200** with a durable `AVAILABLE` rejection snapshot.
- Private `requestedAt` must not be more than two seconds ahead of Inventory's clock. `expiresAt` must be later than `requestedAt` and no more than 600 seconds later. The public Orders service always chooses one original 600-second deadline.

The five `status` values are `AVAILABLE`, `RESERVED`, `PENDING_CONFIRMATION`, `SOLD` and `COMPLIMENTARY`. Orders adds `RESERVING`, `AWAITING_CONFIRMATION`, `CONFIRMING`, `COMPLETED`, `REJECTED` and `EXPIRED` in `workflowStatus`.

`AVAILABLE` on an order with no active assignment does not assert that its historical tickets are free for someone else. `requestedTicketIds` records the request; `activeTicketIds` records the current assignment. Consumers must use the Inventory reservation transaction to acquire tickets, not infer ownership from a list or availability snapshot.

`GET /orders/{orderId}` reads Inventory for the current state and returns 503 when that authority cannot be reached. An initial order without an Inventory record can still be returned as unresolved; reconciliation creates or recovers the durable Inventory result. Reconfirming `SOLD` is idempotent. Confirming an expired nonfinal public order returns 410. The private confirmation route can return the newly materialized expired snapshot on its first boundary observation; an already expired replay is a 409.

Event pagination uses the opaque `cursor` returned by Catalog. Ticket pagination uses the numeric `nextCursor` returned by Inventory. The query parameter is named `cursor` in both APIs, but the token formats are different and must not be mixed.

Errors use `application/problem+json` with `type`, `title`, `status`, `code` and `traceId`. Inventory uses `about:blank`; Catalog/Orders use `urn:ticketing:problem:<code>`. Error bodies do not include exception details. The current 16 KiB decoding limit maps to 400 in Catalog/Orders and 413 in Inventory. OpenAPI lists operation-specific errors instead of claiming that every route emits every status. A deployed gateway may throttle with its own 429 response; this is not a local application rate limiter or a guaranteed Problem response.

Clients should send only documented request fields. The schema does not claim uniform rejection of unknown JSON properties. No cancellation, event update, capacity change or sales reversal endpoint is advertised.

HTTP handlers decode request bodies into DTOs and use MapStruct to project domain results into response DTOs. Catalog and Orders pass the request fields to their use cases. Inventory maps the reservation request and the route's `orderId` into a reservation intent.

## Asynchronous channels

| Logical queue | Producer | Consumer | Envelope `type` |
| --- | --- | --- | --- |
| `inventory-commands` | Catalog outbox | Inventory commands consumer | `ProvisionInventory` |
| `inventory-commands` | Orders outbox | Inventory commands consumer | `PurchaseRequested`, `ComplimentaryRequested`, `ConfirmPurchase` |
| `order-events` | Inventory outbox | Orders | `ReservationStateChanged` |
| `catalog-events` | Inventory outbox | Catalog | `InventoryReady` |
| `inventory-expirations` | Inventory outbox | Independent Inventory expiration consumer | `ExpireReservation` |
| `inventory-expirations` | AWS EventBridge Scheduler or one local recovery emitter | Inventory expiration consumer | `SweepExpiredReservations` |

`EventCreated` is a compatibility alias accepted by Inventory; the current Catalog producer emits `ProvisionInventory`. Every application envelope includes `messageId`, `type`, `schemaVersion: 1`, `aggregateId`, `correlationId`, `causationId`, `occurredAt` and `payload`. Inventory-produced envelopes also include `aggregateVersion`. Transport-level SQS `MessageId` and the stable application `messageId` are distinct identifiers.

`ReservationStateChanged` contains the complete Inventory reservation record, including `requestedAt`, `type` and nullable `actorId`, `reason`, `confirmationId`. It is broader than the Orders projection DTO. The consumer checks identity/deadline consistency and a monotonically increasing `inventoryVersion`; old delivery cannot reverse a final state.

Inventory decodes purchase and complimentary commands into a reservation payload DTO and maps it to a reservation intent. Orders decodes the fields it consumes from `ReservationStateChanged` into a payload DTO and maps that projection to its domain reservation. Catalog handles `InventoryReady` through the application envelope.

Queue names in the contract are logical names. AWS uses an environment prefix supplied by Terraform. Runtime queue URLs come from `INVENTORY_COMMANDS_QUEUE_URL`, `INVENTORY_EXPIRATIONS_QUEUE_URL`, `ORDER_EVENTS_QUEUE_URL` and `CATALOG_EVENTS_QUEUE_URL`. The host-side local SQS endpoint is `http://localhost:19324`; Compose clients use `http://elasticmq:9324` with account path `000000000000`.

Configured queues use SQS Standard semantics, 20-second long polling, 60-second visibility, redrive after five receives, four-day main retention, fourteen-day DLQ retention and a 256 KiB configured message-size limit. The expiration consumer renews visibility during work. A consumer acknowledges only after durable handling; a duplicate or terminal no-op may then be acknowledged safely. Retrying or manually redriving a message must preserve the application identifiers and original deadline.

## Expiration timer and periodic recovery

A successful purchase reservation writes `ExpireReservation` into its outbox in the **same transaction** as reservation, tickets, counters and audit. Its payload is exactly the original `orderId` and `expiresAt`. Complimentary allocations are already final and do not create this timer.

On every publication attempt, the sender computes:

```text
DelaySeconds = min(600, max(0, ceil(expiresAt - currentTime)))
```

The sender does not set `expiresAt = now + 600`. A delayed relay or retry shortens the remaining delay; it never extends the business reservation. Delay and queue delivery are not hard real-time guarantees. Inventory always compares the persisted deadline and current authoritative state before a conditional transition. A message arriving early gets a recalculated visibility delay and no acknowledgement. A duplicate, an old timer for a reassigned ticket, or a timer for `SOLD`/`COMPLIMENTARY` cannot release the current final assignment.

The periodic recovery path remains required by PDF functional requirement 6. AWS Scheduler uses `rate(1 minute)`, no flexible window, a 120-second delivery retry age and at most two scheduler delivery retries. Scheduler delivery has its **own** DLQ, separate from the expiration consumer's redrive DLQ. Its expanded envelope uses `aggregateId=inventory-expiry`, `causationId=scheduler`, execution ID for `messageId`/`correlationId`, and scheduled time for `occurredAt`.

Locally, one `recovery-tick.sh` process sends to the same SQS queue every minute. Its envelope uses `aggregateId=inventory-maintenance` and its generated message ID as `causationId`. The consumer does not use those two metadata values to identify an individual reservation. `LOCAL_RECOVERY_ENABLED=false` is the default and the Compose setting: normal local recovery therefore travels through SQS just like the AWS path. A direct in-process recovery loop is available only as an explicit local-profile fallback.

Before a recovery query, the worker claims the durable `LEASE#EXPIRY_SWEEP` record. The lease lasts 55 seconds, the cooldown is 60 seconds, and cleanup is conditional on its owner. The scan-free indexed query follows `LastEvaluatedKey`, rechecks each authoritative reservation and processes bounded batches. A 45-second budget cancels the sweep before lease expiry. Remaining due rows are rediscovered by later periodic messages; this is not a promise to finish an arbitrary backlog in one sweep. A loser of the lease performs no expiry-partition queries.

Outbox dispatch is also coordinated: a 30-second durable `COORDINATOR#OUTBOX` lease, renewal within ten seconds, per-message leases and an idle backoff from 500 ms to ten seconds. Followers wait for the remaining lease lifetime. The cache only avoids redundant renewal calls; persistent conditional ownership and per-message state remain the correctness boundary. Workers do not independently query all outbox partitions every 500 ms.

Read-time expiry and whole-reservation lazy reclamation remain available if workers or queues are down. The availability freshness check can return 503 instead of presenting overdue counters as fresh. These recovery mechanisms do not permit a confirmation after the immutable deadline.

## Formal validation

Validation was run on 2026-11-16 with registry-verified packages:

| Document | Validator | Result |
| --- | --- | --- |
| OpenAPI 3.1.0 | [Redocly CLI 2.79.0](https://www.npmjs.com/package/@redocly/cli/v/2.79.0), `lint --extends spec` | No specification errors. |
| AsyncAPI 3.0.0 | Official [AsyncAPI parser 3.6.6](https://www.npmjs.com/package/@asyncapi/parser/v/3.6.6) | Parsed document, zero errors; informational recommendation to adopt AsyncAPI 3.1.0. The existing valid 3.0.0 contract is intentionally retained. |

Reproduce from the repository root with Node.js 24 LTS:

```powershell
npm exec --yes --package=@redocly/cli@2.79.0 -- redocly lint contracts/openapi.json --extends spec
npm install --prefix work/contract-tools --save-exact --ignore-scripts --no-audit --no-fund @asyncapi/parser@3.6.6
node -e "const fs=require('node:fs');const {Parser}=require('./work/contract-tools/node_modules/@asyncapi/parser');new Parser().parse(fs.readFileSync('contracts/asyncapi.json','utf8')).then(r=>{const e=r.diagnostics.filter(d=>d.severity===0);console.log(r.diagnostics);if(!r.document||e.length)process.exitCode=1}).catch(e=>{console.error(e);process.exitCode=1})"
```

These checks use the tools' real specification validators, not a home-grown success check. The parser's zero-based severity `0` denotes an error. Runtime behavioral evidence remains in the service tests and acceptance reports; formal contract validation does not replace those tests. The existing Postman collection is preserved separately in [ticketing.postman_collection.json](../contracts/ticketing.postman_collection.json).

## Owning sources

- Catalog [HTTP handler](../services/catalog/infrastructure/entry-points/reactive-web/src/main/java/com/ticketing/catalog/api/RequestHandler.java), [use case](../services/catalog/domain/usecase/src/main/java/com/ticketing/catalog/usecase/CatalogUseCase.java) and [event pagination](../services/catalog/infrastructure/driven-adapters/dynamo-db/src/main/java/com/ticketing/catalog/dynamodb/EventRepository.java).
- Orders [HTTP handler](../services/orders/infrastructure/entry-points/reactive-web/src/main/java/com/ticketing/orders/api/RequestHandler.java), [use case](../services/orders/domain/usecase/src/main/java/com/ticketing/orders/usecase/OrdersUseCase.java) and [order projection](../services/orders/domain/model/src/main/java/com/ticketing/orders/model/Order.java).
- Inventory [HTTP router](../services/inventory/infrastructure/entry-points/reactive-web/src/main/java/com/ticketing/inventory/api/Routes.java), [use case](../services/inventory/domain/usecase/src/main/java/com/ticketing/inventory/usecase/InventoryUseCase.java), [transaction adapter](../services/inventory/infrastructure/driven-adapters/dynamo-db/src/main/java/com/ticketing/inventory/dynamodb/InventoryRepository.java), [SQS producer](../services/inventory/infrastructure/driven-adapters/sqs-sender/src/main/java/com/ticketing/inventory/sqs/sender/SqsMessagePublisher.java) and [consumer](../services/inventory/infrastructure/entry-points/sqs-listener/src/main/java/com/ticketing/inventory/sqs/listener/SqsCommandProcessor.java).
- Local [periodic emitter](../scripts/local/recovery-tick.sh) and [Compose](../docker-compose.yml). The companion `ticketing-infra` repository owns `infra/modules/messaging/main.tf` and `scheduler.tf`; this application repository does not define a second AWS schedule.
