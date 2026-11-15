# Runtime roles and recovery evidence

Each context has an API role and a worker role using the same image. API replicas disable workers; worker replicas expose only health and Prometheus internally. Workers have no published host ports. The local runtime verification also checks the dedicated expiration queue, its DLQ, a single healthy recovery publisher and disabled direct local sweeps.

## Deadlines and expiration

`expiresAt` remains immutable and at most 600 seconds after original order acceptance. A held reservation writes its `ExpireReservation` timer into the transactional outbox. Publication uses the remaining deadline as the SQS message delay; retries cannot extend the reservation. The consumer uses conditional state transitions, so a delayed or duplicate timer cannot release SOLD tickets.

The dedicated `inventory-expirations` queue has a 60 second visibility timeout, 20 second long polling and a separate DLQ after five receives. EventBridge Scheduler sends one recovery sweep per minute in AWS. Locally, the single `expiry-recovery` container publishes the same envelope to ElasticMQ; it is not an EventBridge emulator. Workers do not perform a direct local scheduled sweep in Compose. Recovery acquires a global lease before querying the expiration index.

Delivery and materialization can lag behind the business deadline. Confirmation and allocation must enforce the immutable deadline regardless of consumer availability. Recovery measurements are not promises of exact delivery time.

## Polling and bounded retries

Catalog and Orders outbox polling acquire a DynamoDB coordinator lease before their indexed queries. Orders reconciliation uses a separate coordinator. These leases coordinate query work; per-record conditional writes remain responsible for correctness.

The lease lasts 30 seconds and is renewed while the leader is idle. Empty outbox polling backs off with jitter to 10 seconds; empty Orders reconciliation backs off to 60 seconds. Followers use the incumbent lease deadline before trying again. A local scheduler tick does not imply a database query.

Orders checks stable RESERVED records every 60 seconds and pending intents every five seconds. A failed recovery persists a 5/10/20/40/60 second retry delay without removing the due index or modifying the immutable business intent. Version conditions prevent an old recovery from overwriting a newer projection. Failure of one record does not cancel recovery of the rest of the bounded batch. Each due query is limited to 25 records across 16 shards, with at most eight simultaneous Inventory operations.

## Observed local development results

These results were recorded before the final clean commit and do not establish AWS IAM behavior, AWS pricing or production performance.

| Check | Observed result |
|---|---|
| Role isolation | Three API contexts, two Inventory worker replicas, worker business routes rejected, management probes healthy |
| Consumer outage | Event remained PROVISIONING while Inventory workers were stopped and became ACTIVE after restoration; Inventory API stayed ready |
| Consumer recovery duration | 67.52 seconds in the recorded run, including outage, startup and redelivery |
| Same-ticket contention | 100 buyers, one winner, 99 rejected; counter invariant preserved |
| Authority outage | Inventory API and all consumers stopped, then restored; accepted order recovered without changing its deadline |

One earlier recovery probe used a 45 second wait, shorter than the queue's 60 second visibility timeout, and timed out. The verifier now allows 120 seconds to include an in-flight receive, redelivery and outbox propagation. This changes only the verification budget; the reservation deadline is unchanged.

A separate 60 second read-only Prometheus measurement ran while Inventory API was deliberately unavailable:

| Context | Worker replicas | DynamoDB Query | UpdateItem | GetItem |
|---|---:|---:|---:|---:|
| Catalog | 1 | 96 | 3 | 0 |
| Orders | 1 | 256 | 9 | 3 |

Catalog's observed 1.6 queries/second matches a 16-shard empty outbox poll every 10 seconds. Orders was actively recovering pending records; its 4.27 queries/second is not an idle result. Lease renewal and conditional retry writes remain part of the work. These are SDK operation counts, not billed capacity units or monetary costs, and they do not independently prove the rate with two Orders workers.

Reproduce role and consumer-recovery verification with:

```sh
python scripts/local/verify_roles.py --min-inventory-workers 2 --provision-recovery
```

Evidence is written under `build/quality/`; final promotion requires fresh evidence for the exact committed candidate and approved image digests.
