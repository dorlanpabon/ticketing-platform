# Ticketing Platform

Three reactive services generated with Bancolombia Scaffold Clean Architecture 5.2.1: Catalog, Orders and Inventory. Java 25, Spring Boot 4.1.2, DynamoDB and SQS.

## Status

Implementation in progress. Verification results are recorded under `docs/evidence`; architecture targets are not test results. No AWS resources are deployed automatically.

## Business contract

`202 Accepted` confirms durable work, not a guaranteed reservation. A purchase contains 1–10 distinct numbered tickets from one event. Reservation deadlines are immutable, at most 600 seconds after initial acceptance. Only Inventory assigns tickets. Only SOLD tickets count as sales; COMPLIMENTARY is separate.

An order with AVAILABLE status has no active allocation; this does not assert that its historical requested tickets are currently free. `workflowStatus`, `resolution`, requested and active ticket IDs disambiguate the response.

## Delivery flow

`feature/*`, `fix/*`, `chore/*` start from `develop`, then merge into `develop`, `release` and `main`. DEV validation includes Sonar before promotion. Commits retain their real timestamps. Environment configuration comes from Terraform; promotion reuses approved image digests.

## Architecture sources

- [Implementation plan](docs/planning/PLAN-MAESTRO.md)
- [Requirements matrix](docs/planning/MATRIZ-REQUISITOS.md)
- [Architecture diagrams](docs/planning/DIAGRAMAS.md)

Generated infrastructure examples are adapted to real ports and workflows. Business domain types are owned independently by each service.
