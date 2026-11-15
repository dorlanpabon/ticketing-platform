# Ticketing Platform

Three reactive services generated with Bancolombia Scaffold Clean Architecture 5.2.1: Catalog, Orders and Inventory. Java 25, Spring Boot 4.1.2, DynamoDB and SQS.

## Status

The local runtime, contracts and quality tooling are implemented. `docs/evidence` maps requirements to code and records development observations. Generated verification reports under `build/quality` identify the tested SHA, dirty state, runtime and limits; architecture targets are not test results. No AWS resources are deployed automatically. Terraform is maintained in the independent `ticketing-infra` repository.

## Business contract

`202 Accepted` confirms durable work, not a guaranteed reservation. A purchase contains 1–10 distinct numbered tickets from one event. Reservation deadlines are immutable, at most 600 seconds after initial acceptance. Only Inventory assigns tickets. Only SOLD tickets count as sales; COMPLIMENTARY is separate.

An order with AVAILABLE status has no active allocation; this does not assert that its historical requested tickets are currently free. `workflowStatus`, `resolution`, requested and active ticket IDs disambiguate the response.

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

Scale consumers independently with `docker compose up -d --wait --scale inventory-worker=2` (also supported for `catalog-worker` and `orders-worker`). Conditional DynamoDB writes, inbox deduplication and outbox leases coordinate replicas. Run `python scripts/local/verify_roles.py --min-inventory-workers 2` to record actual runtime identities and probe evidence. This verifies role isolation and replica readiness; the concurrency/recovery acceptance suite separately verifies business correctness.

The default six JVMs are capped at 512 MiB each with a 70% maximum heap; DynamoDB and ElasticMQ add 768 MiB. Sonar is optional and needs up to 3 GiB plus PostgreSQL. On an 8 GiB Docker VM, stop the quality profile before scaling workers or running parallel image builds. The infrastructure repository scales the six ECS services independently and reuses three approved image digests.

| Service | Local URL |
|---|---|
| Catalog | `http://localhost:18080` |
| Orders | `http://localhost:18081` |
| Inventory | `http://localhost:18082` |

Local HTTP requests use `Authorization: Bearer local-customer-1`, `local-customer-2`, or `local-admin`. These synthetic identities work only with the local authentication mode/profile. AWS uses JWT validation at the API gateway and application ownership/role checks.

The local profile uses DynamoDB Local and ElasticMQ with persistent volumes. These are development emulators. Passing local tests does not prove IAM behavior or complete AWS service compatibility. No business fixtures are inserted or overwritten on restart.

Expiration uses a delayed `ExpireReservation` message per held reservation in the dedicated `inventory-expirations` queue, with a separate DLQ. A single lightweight `expiry-recovery` container sends `SweepExpiredReservations` every minute for recovery through the same queue and consumer path. It is a local publisher, not an EventBridge emulator. AWS uses EventBridge Scheduler; workers do not run a local sweep schedule in Compose. The business deadline remains the original `expiresAt`, even during delivery or consumer delays.

Orders recovery acquires its coordinator lease before querying the due index. Stable reservations are rechecked every 60 seconds; pending intents use 5 seconds, and failed records persist a 5/10/20/40/60 second retry delay. Empty index polling backs off with jitter, and non-owner replicas wait for the lease.

For local Sonar, run `python scripts/local/setup_quality.py` once, then `docker compose --profile quality up -d sonar`. The interface binds to `http://localhost:19000`. Project/gate provisioning is in `scripts/quality/sonar_setup.py`. Never commit its credentials or analysis tokens.

Public and asynchronous specifications are in `contracts/openapi.json` and `contracts/asyncapi.json`. Internal Inventory endpoints are not exposed through the AWS public gateway.

Generated example classes are retained and adapted: `DynamoDBTemplateAdapter`, `ModelEntity`, `TemplateAdapterOperations`, `SQSSender`, `SQSProcessor`, `SQSListener`, `RestConsumer`, its configuration and request/response models. The scaffold regenerates its architecture test/helper with machine-specific paths; those generated test files remain local and are regenerated during builds.
