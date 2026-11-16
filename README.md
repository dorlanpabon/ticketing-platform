# Ticketing Platform

Three reactive services generated with Bancolombia Scaffold Clean Architecture 5.2.1: Catalog, Orders and Inventory. Java 25, Spring Boot 4.1.2, DynamoDB and SQS.

## Status

The local runtime, contracts and quality tooling are implemented. `docs/evidence` maps requirements to code and records development observations. Generated verification reports under `build/quality` identify the tested SHA, dirty state, runtime and limits; architecture targets are not test results. No AWS resources are deployed automatically. Terraform is maintained in the independent `ticketing-infra` repository.

## Business contract

For order creation, `202 Accepted` confirms the durable order and successful enqueue in SQS. Inventory assigns tickets asynchronously. Orders stores the order and outbox command in one DynamoDB transaction and uses the same outbox entry for publication by the API or relay. If Orders cannot confirm enqueue, it returns `503 ORDER_ENQUEUE_UNAVAILABLE`. Retry the same request with the same `Idempotency-Key` to recover the original order and deadline.

A purchase contains 1–10 distinct numbered tickets from one event. The reservation deadline is fixed when Orders stores the order, at most 600 seconds after creation. Only SOLD tickets count as sales. COMPLIMENTARY is separate.

An order with AVAILABLE status has no active allocation; this does not assert that its historical requested tickets are currently free. `workflowStatus`, `resolution`, requested and active ticket IDs disambiguate the response.

Catalog reads event validation and pagination settings from `catalog.limits`. They may be tightened by environment within the published API bounds. The configured default page size must stay within the configured page range. [Catalog application.yaml](services/catalog/applications/app-service/src/main/resources/application.yaml)

## Delivery flow

`feature/*`, `fix/*`, `chore/*` start from `develop`, then merge into `develop`, `release` and `main`. DEV validation includes Sonar before promotion. Commits retain their real timestamps. Environment configuration comes from Terraform; promotion reuses approved image digests.

## Architecture sources

- [Implementation plan](docs/planning/PLAN-MAESTRO.md)
- [Requirements matrix](docs/planning/MATRIZ-REQUISITOS.md)
- [Architecture diagrams](docs/planning/DIAGRAMAS.md)
- [Current runtime and expiration flow](docs/architecture-runtime.md)
- [Architecture decisions](docs/decisions.md)
- [Implementation evidence](docs/evidence/requirements.md)
- [Operations runbook](docs/runbook.md)

Generated infrastructure examples are adapted to real ports and workflows. Business domain types are owned independently by each service.

## Local execution

Requires Docker Compose. Run `docker compose up --build --wait` from this repository. No AWS account is required for this local profile.

Each context runs as two independent processes using the same image: an HTTP API and an asynchronous worker. APIs set `APP_ROLE=api`, `API_ENABLED=true`, `WORKERS_ENABLED=false`; workers invert those flags and have no published host ports. Worker HTTP business routes return 404 while readiness and Prometheus remain available. Runtime flags complement network/IAM isolation; they do not replace it.

Run `python scripts/acceptance/verify.py --allow-dirty --checks queueOutage` against this local stack to verify recovery with the same idempotency key. The check pauses LocalStack and stops the Orders and Inventory workers, then restores them.

Scale consumers independently with `docker compose up -d --wait --scale inventory-worker=2` (also supported for `catalog-worker` and `orders-worker`). Conditional DynamoDB writes, inbox deduplication and outbox leases coordinate replicas. Run `python scripts/local/verify_roles.py --min-inventory-workers 2` to record actual runtime identities and probe evidence. This verifies role isolation and replica readiness; the concurrency/recovery acceptance suite separately verifies business correctness.

The default six JVMs are capped at 512 MiB each with a 70% maximum heap. DynamoDB Local and LocalStack run in separate containers with their own resource limits. Sonar is optional and needs up to 3 GiB plus PostgreSQL. On an 8 GiB Docker VM, stop the quality profile before scaling workers or running parallel image builds. The infrastructure repository scales the six ECS services independently and reuses three approved image digests.

| Service | Local URL |
|---|---|
| Catalog | `http://localhost:18080` |
| Orders | `http://localhost:18081` |
| Inventory | `http://localhost:18082` |
| LocalStack SQS | `http://localhost:4566` |

Local HTTP requests use `Authorization: Bearer local-customer-1`, `local-customer-2`, or `local-admin`. These synthetic identities work only with the local authentication mode/profile. AWS uses JWT validation at the API gateway and application ownership/role checks.

The local profile uses DynamoDB Local for tables and LocalStack Community exclusively for SQS. Queue initialization creates four queues and their dead-letter queues before the APIs and workers start. DynamoDB Local keeps its tables in a named volume. LocalStack keeps messages in memory and recreates empty queues on restart. Business fixtures are created explicitly by the acceptance scripts. AWS IAM, KMS and service limits require AWS verification.

Expiration uses a delayed `ExpireReservation` message per held reservation in the dedicated `inventory-expirations` queue, with a separate DLQ. A single lightweight `expiry-recovery` container sends `SweepExpiredReservations` every minute for recovery through the same queue and consumer path. It is a local publisher, not an EventBridge emulator. AWS uses EventBridge Scheduler; workers do not run a local sweep schedule in Compose. The business deadline remains the original `expiresAt`, even during delivery or consumer delays.

Orders recovery acquires its coordinator lease before querying the due index. Stable reservations are rechecked every 60 seconds; pending intents use 5 seconds, and failed records persist a 5/10/20/40/60 second retry delay. Empty index polling backs off with jitter, and non-owner replicas wait for the lease.

For local Sonar, run `python scripts/local/setup_quality.py` once, then `docker compose --profile quality up -d sonar`. The interface binds to `http://localhost:19000`. Project/gate provisioning is in `scripts/quality/sonar_setup.py`. Never commit its credentials or analysis tokens.

Public and asynchronous specifications are in `contracts/openapi.json` and `contracts/asyncapi.json`. Internal Inventory endpoints are not exposed through the AWS public gateway.

The scaffold adapters and their tests are retained and named for their responsibilities: event, order and inventory repositories; event listeners and command publishers; and the Orders HTTP adapter for Inventory. The scaffold regenerates its architecture test/helper with machine-specific paths; those generated test files remain local and are regenerated during builds.

Java source uses explicit imports and four spaces for nesting and line continuations. Leave blank lines between methods and related declarations. Keep fixed protocol fields in constants classes within their own module and configurable values in YAML. Log variable data as key–value fields.

## API examples

Run these PowerShell blocks in order after the services are healthy. The example creates a fresh event and uses the local admin and customer identities. Each write has its own idempotency key.

```powershell
$catalog = 'http://localhost:18080/api/v1/events'
$ordersApi = 'http://localhost:18081/api/v1'
$orders = "$ordersApi/orders"
$inventory = 'http://localhost:18082/api/v1/events'
$admin = @{ Authorization = 'Bearer local-admin' }
$customer = @{ Authorization = 'Bearer local-customer-1' }
$runId = [guid]::NewGuid().ToString()

function Wait-ForStatus($uri, $headers, $property, $expected) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(60)
    do {
        $result = Invoke-RestMethod -Uri $uri -Headers $headers
        if ($result.$property -eq $expected) { return $result }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Expected $property to be $expected. Last value was $($result.$property)."
}
```

Create an event as the admin. The response contains its ID, and Catalog lists it after Inventory provisions its numbered tickets.

```powershell
$eventBody = @{
    name = 'Ticketing demo'
    venue = 'Local arena'
    startsAt = [DateTimeOffset]::UtcNow.AddDays(7).ToString('o')
    capacity = 20
} | ConvertTo-Json
$event = Invoke-RestMethod -Method Post -Uri $catalog -ContentType 'application/json' -Body $eventBody -Headers ($admin + @{ 'Idempotency-Key' = "$runId-event" })
$eventId = $event.eventId
Wait-ForStatus "$catalog/$eventId" $customer 'status' 'ACTIVE'
Invoke-RestMethod -Uri "${catalog}?limit=5" -Headers $customer
```

Request tickets 1 and 2 as a customer. The `202` response includes the order ID after SQS accepts the command. Poll the order until it is ready to confirm, then confirm before `expiresAt`.

```powershell
$orderBody = @{
    eventId = $eventId
    ticketIds = @('1', '2')
} | ConvertTo-Json
$purchaseHeaders = $customer + @{ 'Idempotency-Key' = "$runId-purchase" }
$order = Invoke-RestMethod -Method Post -Uri $orders -ContentType 'application/json' -Body $orderBody -Headers $purchaseHeaders
$orderUri = "$orders/$($order.orderId)"
Wait-ForStatus $orderUri $customer 'workflowStatus' 'AWAITING_CONFIRMATION'
Invoke-RestMethod -Method Post -Uri "$orderUri/confirm" -Headers ($customer + @{ 'Idempotency-Key' = "$runId-confirm" })
Wait-ForStatus $orderUri $customer 'status' 'SOLD'
```

Assign ticket 3 as a complimentary ticket, then check availability. The response should show 17 available, 2 sold and 1 complimentary.

```powershell
$courtesyBody = @{
    eventId = $eventId
    ticketIds = @('3')
    beneficiaryId = 'local-customer-1'
    reason = 'Guest speaker'
} | ConvertTo-Json
$courtesy = Invoke-RestMethod -Method Post -Uri "$ordersApi/admin/complimentary-orders" -ContentType 'application/json' -Body $courtesyBody -Headers ($admin + @{ 'Idempotency-Key' = "$runId-courtesy" })
Wait-ForStatus "$orders/$($courtesy.orderId)" $customer 'status' 'COMPLIMENTARY'
Invoke-RestMethod -Uri "$inventory/$eventId/availability" -Headers $customer
```

For an automated walkthrough, use the [Postman collection](contracts/ticketing.postman_collection.json) and its [local runner instructions](scripts/postman/README.md).
