# INTERVIEW_PREPARATION.md

## Shipment Integrity Service – Interview Questions & Strong Answers

---

# Question 1

## Can you walk me through your role in this system and explain what core problem you were trying to solve?

### Strong Answer

I was acting as a consulting software engineer responsible for designing and implementing a shipment integrity service for an e-commerce platform.

The core problem was that courier partners emit webhook events that are not always reliable. Events can arrive late, out of order, duplicated, or contain conflicting information. As a result, downstream systems such as customer support, order tracking, and incident management were deriving shipment state independently and often disagreed about the current status of a shipment.

My role was to work with the client to clarify requirements, resolve ambiguities, and design a service that became the single source of truth for shipment status.

The solution ingests courier events, deduplicates them, resolves the authoritative shipment state using a consistent ordering strategy, stores a complete audit trail, and exposes APIs that downstream systems can trust instead of implementing their own state derivation logic.

The goal was to ensure every consumer gets the same answer, derived the same way, every time.

---

# Question 2

## How does your system ensure it produces a correct shipment state despite duplicate, late, or out-of-order events?

### Strong Answer

The system follows a strict processing pipeline.

First, incoming events are deduplicated using the composite key `(partner, eventId)` because event IDs are only unique within a partner.

After deduplication, events are evaluated using `receivedAt`, which is the authoritative ordering signal. If an event arrives with a timestamp older than the current shipment state, it is stored but marked as out-of-order and does not update the current state.

If the event is newer, it is passed to the state resolver. The resolver validates the transition against the shipment state machine. Invalid transitions are rejected from state updates but recorded in the audit log.

Valid transitions result in:

* Derived event creation
* Shipment state update
* Audit decision logging

All events are retained, all decisions are auditable, and state changes occur only through valid transitions.

---

# Question 3

## Why did you choose `receivedAt` over `occurredAt`?

### Strong Answer

We chose `receivedAt` because it is generated at the ingestion boundary and is therefore under our control.

The client identified that some courier partners, particularly Partner B, can backfill or generate unreliable `occurredAt` timestamps due to clock skew or delayed event generation. This makes `occurredAt` unsuitable as an ordering signal.

Using `receivedAt` gives us a deterministic and consistent ordering mechanism across all partners.

If we incorrectly used `occurredAt`, events could be processed in the wrong order. For example, a delayed IN_TRANSIT event could be processed after a DELIVERED event and potentially lead to incorrect state transitions or state regressions.

The system's correctness depends heavily on having a trustworthy ordering signal, which is why ADR-001 explicitly selected `receivedAt`.

---

# Question 4

## What would you monitor first in production?

### Strong Answer

I would focus on throughput, correctness, and latency.

First, I would monitor ingestion rate per partner to detect spikes, drops, or integration failures.

Second, I would monitor correctness metrics such as:

* Out-of-order rate
* Transition failure rate
* Idempotency conflicts
* Reconciliation drift

These tell us whether the system is producing accurate state.

Third, I would monitor latency metrics:

* Ingestion latency
* State resolution latency
* End-to-end processing latency

Finally, I would monitor persistence metrics such as:

* Database write latency
* Database error rate
* Lock contention

Together these provide visibility into both system health and business correctness.

---

# Question 5

## Why did you choose SQLite and when would you migrate to PostgreSQL?

### Strong Answer

SQLite was chosen because it offers low operational complexity and is entirely sufficient for the expected Phase 1 workload.

The primary limitation is its single-writer model. As sustained write throughput increases, lock contention becomes a bottleneck.

I would consider migration when:

* Sustained writes approach 500–1000 writes/sec
* Multiple courier partners are onboarded
* Write latency begins violating SLOs
* Lock contention becomes visible

The architecture isolates persistence behind repository interfaces, so the business logic remains unchanged.

The migration strategy would be:

1. Introduce PostgreSQL alongside SQLite
2. Enable dual-write through feature flags
3. Validate consistency through reconciliation
4. Migrate historical data
5. Cut over reads and writes
6. Remove SQLite

The domain model, state resolver, and processing pipeline remain unchanged.

---

# Question 6

## The ADR specified `receivedAt` but the code still used `occurredAt`. How would you prevent that happening again?

### Strong Answer

The root cause was a disconnect between the ADR and implementation.

ADR-001 defined `receivedAt` as authoritative, but the resolver still partially relied on `occurredAt`. The issue remained invisible because tests used identical timestamps, so ordering behaviour was never truly exercised.

To prevent this:

1. Introduce ADR-to-code traceability
2. Add architecture tests enforcing ordering behaviour
3. Introduce timestamp divergence test fixtures
4. Add integration tests covering out-of-order scenarios
5. Add regression tests specifically targeting this bug

The lesson is that documentation alone is insufficient. Critical architectural decisions must be enforced through automated tests.

---

# Question 7

## How would you support corrections to terminal shipment states?

### Strong Answer

The current design intentionally uses an append-only model.

Directly modifying shipment state would violate auditability and make historical reconstruction difficult.

Instead, I would introduce a dedicated correction event type.

The correction event would contain:

* Correction reason
* Requestor
* Approver
* Timestamp
* Original state
* Corrected state

The correction would be appended to the event stream rather than modifying existing records.

The derived state could then be recomputed using both operational events and correction events.

This preserves:

* Auditability
* Replayability
* Traceability

while supporting real-world correction workflows.

---

# Question 8

## What would you add first before taking this system to production?

### Strong Answer

I would prioritise improvements in three phases.

### 1. Observability

Add:

* Metrics
* Distributed tracing
* Structured logging
* Alerting

This gives visibility into correctness and performance issues before customers notice them.

### 2. Correctness Hardening

Add:

* ADR-to-code validation
* Integration tests
* Timestamp divergence tests
* Replay validation tests

These reduce the risk of hidden correctness defects.

### 3. Operational Maturity

Add:

* Correction event API
* PostgreSQL migration readiness
* Capacity planning
* Incident response runbooks

The focus is to first ensure we can trust the data, then ensure we can scale the platform safely.

---

# Question 9

## Why didn't you use Kafka?

### Strong Answer

Kafka is an excellent technology, but I deliberately chose not to introduce it in Phase 1.

The system receives webhook calls and processes them synchronously. There is only one consumer of the events and no requirement for high-volume stream processing.

Adding Kafka would increase:

* Operational complexity
* Deployment complexity
* Monitoring requirements

without solving a current problem.

My approach was to optimise for simplicity and defer Kafka until there was evidence that asynchronous decoupling or large-scale event fan-out was required.

---

# Question 10

## Why didn't you implement full Event Sourcing?

### Strong Answer

The architecture borrows some event sourcing concepts but stops short of full event sourcing.

Full event sourcing would require rebuilding shipment state from the entire event stream on every query or maintaining projections.

Our primary read use case is retrieving the current shipment status quickly.

By maintaining:

* Append-only event history
* Derived current state

we get:

* O(1) status lookups
* Full audit history
* Replay capability

while avoiding the complexity of a fully event-sourced architecture.

---

# Question 11

## How would you detect the receivedAt bug if it reached production?

### Strong Answer

The first indicators would likely be correctness metrics.

I would expect to see:

* Increased out-of-order rates
* Elevated transition failures
* State reconciliation mismatches
* Customer reports of inconsistent shipment state

The most reliable detection mechanism would be a reconciliation process that periodically rebuilds shipment state from raw events and compares the result with the stored derived state.

Because the system retains all raw events, we can replay history, identify affected shipments, and rebuild state after the fix is deployed.

---

# Quick Interview Summary

## Key Architectural Principles

* Use `receivedAt` for ordering
* Deduplicate using `(partner, eventId)`
* Append-only event store
* Derived current state
* Replay capability
* Auditability first
* Per-event batch isolation
* Repository abstraction for persistence

## Biggest Production Risks

* Incorrect ordering logic
* Out-of-order partner behaviour
* Missing correction mechanism
* SQLite write contention
* Lack of observability

## Production Priorities

1. Observability
2. Correctness validation
3. Correction workflows
4. PostgreSQL migration readiness
5. Operational runbooks
