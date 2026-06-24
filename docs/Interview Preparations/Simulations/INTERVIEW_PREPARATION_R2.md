# INTERVIEW_QA_SESSION_1.md

## Shipment Integrity Service – Interview Questions & Strong Answers (Initial Interview Round)

These are the questions from the initial interview simulation together with refined senior-level answers suitable for technical interviews.

---

# Question 1

## Can you walk me through your role in this system and explain what the core problem you were trying to solve?

### Strong Answer

I was acting as a consulting software engineer responsible for designing and implementing a Shipment Integrity Service for an e-commerce platform.

The business problem was that courier partners emit shipment events that are often unreliable. Events can arrive late, out of order, duplicated, or contain conflicting information. As a result, downstream systems such as customer support, order tracking, and incident management were independently deriving shipment state and frequently disagreed about a shipment's actual status.

My role involved both technical implementation and requirements clarification. Several aspects of the problem were underspecified, including the deduplication strategy, authoritative timestamp selection, and batch processing behaviour. I worked with the client to resolve these ambiguities and document the decisions through ADRs.

The resulting service provides a single source of truth. It ingests courier events, deduplicates them, resolves authoritative shipment state, stores a complete audit trail, and exposes APIs so downstream systems no longer need to derive shipment status themselves.

The objective was simple: every consumer should receive the same answer, derived using the same logic, every time.

---

# Question 2

## How does your system ensure it still produces a correct and consistent shipment state despite duplicate, late, or out-of-order events?

### Strong Answer

The system follows a deterministic processing pipeline.

First, incoming events are deduplicated using the composite key `(partner, eventId)` because event IDs are not globally unique across courier partners.

After deduplication, the system evaluates ordering using `receivedAt`, which ADR-001 defines as the authoritative timestamp.

If an event is older than the currently accepted state, it is still stored for audit purposes but is marked as out-of-order and does not update the shipment's current state.

If the event is newer, it is passed to the `ShipmentStateResolver`, which validates whether the proposed transition is allowed according to the shipment state machine.

Invalid transitions are rejected from state updates but are recorded in the audit log. Valid transitions result in:

* Derived event creation
* Current state update
* Audit logging

All raw events are retained, all decisions are auditable, and state changes occur only through valid transitions, ensuring consistency and replayability.

---

# Question 3

## Why did you choose `receivedAt` over `occurredAt`, and what would break if that assumption turned out to be wrong?

### Strong Answer

We chose `receivedAt` because it is the most trustworthy timestamp available.

The client confirmed that some courier partners, particularly Partner B, may backfill or generate unreliable `occurredAt` timestamps due to clock skew, delayed processing, or operational workflows. That makes `occurredAt` unsuitable for determining authoritative ordering.

`receivedAt` represents when the partner's system ingested or emitted the event and was identified as the most reliable ordering signal available.

If that assumption were wrong, the consequences would be significant.

Events could be processed in the wrong sequence, resulting in:

* Incorrect shipment state
* Invalid state transitions being accepted
* Legitimate state updates being ignored
* Inconsistent downstream views of shipment status

For example, a delayed IN_TRANSIT event could incorrectly be processed after a DELIVERED event and affect the shipment's derived state.

The entire correctness model depends on having a reliable ordering signal, which is why ADR-001 explicitly documents the decision.

---

# Question 4

## If this service were deployed and running at scale, what are the first 2–3 things you would instrument or monitor to ensure you can trust the system is working correctly?

### Strong Answer

I would focus on three categories: throughput, correctness, and latency.

### Throughput

I would monitor:

* Event ingestion rate per partner
* Database write throughput
* Request failure rate

These metrics tell us whether the system can sustain incoming traffic.

### Correctness

I would monitor:

* Out-of-order rate
* Transition failure rate
* Idempotency conflict rate
* Derived state reconciliation drift

These metrics indicate whether the system is producing correct shipment state.

### Latency

I would monitor:

* Ingestion latency
* State resolution latency
* End-to-end processing latency

These metrics tell us how quickly shipment status becomes available to downstream consumers.

Together these provide visibility into both operational health and business correctness.

---

# Question 5

## You currently use an append-only event store and derived current state model. If you had to scale this system 10x and move from SQLite to PostgreSQL, what would change and what would remain the same?

### Strong Answer

SQLite was selected because it offers minimal operational overhead and is sufficient for the expected Phase 1 workload.

The primary limitation is its single-writer architecture. As throughput grows, lock contention and write latency become constraints.

I would consider migration when:

* Sustained write throughput approaches 500–1000 writes/sec
* Multiple courier partners significantly increase event volume
* Database latency begins violating SLOs
* Lock contention becomes visible

The migration would be performed incrementally:

1. Introduce PostgreSQL alongside SQLite
2. Enable dual-write using feature flags
3. Run reconciliation checks to validate consistency
4. Migrate historical data
5. Cut over reads and writes
6. Remove SQLite after verification

What changes:

* Connection pool configuration
* SQL dialect differences
* Database infrastructure

What remains the same:

* Domain model
* Resolver logic
* Deduplication strategy
* Event processing pipeline
* Repository interfaces

The repository abstraction isolates business logic from persistence concerns, making the migration relatively low risk.

---

# Question 6

## How would you redesign your development and testing process so that a mismatch between ADR-001 and implementation would be caught before production?

### Strong Answer

The root cause was a disconnect between architectural intent and implementation.

ADR-001 specified that `receivedAt` was authoritative, but the resolver still partially relied on `occurredAt`. The issue remained invisible because test fixtures used identical timestamp values, so ordering behaviour was never exercised.

To prevent this class of defect:

### 1. ADR-to-Code Traceability

Critical architectural decisions should be linked to automated tests.

If ADR-001 defines ordering behaviour, there should be tests explicitly validating that behaviour.

### 2. Timestamp Divergence Test Fixtures

Tests should intentionally use different values for:

* occurredAt
* receivedAt

to verify ordering logic.

### 3. Integration Testing

End-to-end event sequences should be tested using realistic out-of-order scenarios.

### 4. Regression Tests

A dedicated regression test should reproduce the exact bug scenario to ensure it never reappears.

The lesson is that important architectural decisions should be executable and verifiable, not just documented.

---

# Question 7

## Your system currently has no first-class mechanism for correcting terminal shipment states. What problems does this create, and how would you design a correction mechanism?

### Strong Answer

The current state model assumes shipment state progresses through courier-driven lifecycle events.

In reality, operational mistakes occur:

* Incorrect delivery scans
* Lost packages marked delivered
* Human data entry errors

Without a correction mechanism, operators are forced to make direct database changes, which breaks auditability and traceability.

I would introduce a dedicated Correction Event.

The correction event would contain:

* Original state
* Corrected state
* Reason
* Requestor
* Approver
* Timestamp

Rather than modifying existing records, the correction would be appended to the event stream.

This preserves:

* Append-only guarantees
* Auditability
* Replayability

while supporting operational corrections.

I would keep correction events separate from the shipment state machine because they represent administrative actions rather than courier lifecycle transitions.

---

# Question 8

## If you were responsible for taking this from a thin-slice implementation to a production-grade service used across multiple courier partners, what would be the first three things you would add or change?

### Strong Answer

I would approach this in three phases.

---

## Phase 1: Observability

Add:

* Metrics
* Distributed tracing
* Structured logging
* Alerting

This provides visibility into throughput, correctness, latency, and system health.

Without observability, operational issues are discovered by customers rather than engineers.

---

## Phase 2: Correctness Hardening

Add:

* ADR-to-code validation tests
* Integration testing
* Timestamp divergence test suites
* Replay validation tests

This addresses the class of bugs that caused the receivedAt/occurredAt mismatch.

The goal is to ensure architectural decisions are continuously enforced.

---

## Phase 3: Operational Maturity

Add:

* Correction event API
* PostgreSQL migration readiness
* Capacity planning
* Incident response runbooks

These changes prepare the system for increased traffic, multiple partners, and real-world operational workflows.

---

# Interview Closing Summary

## Key Themes to Reinforce

### Architecture

* Append-only event store
* Derived current state
* Replay capability
* Audit-first design

### Correctness

* receivedAt ordering
* Deduplication via `(partner, eventId)`
* State-machine validation
* Out-of-order event handling

### Production Readiness

* Metrics and observability
* Replay and reconciliation
* Incident response
* PostgreSQL migration path

### Ownership

* Admit the ADR mismatch bug
* Explain how it happened
* Explain the process improvements introduced afterward

Senior interviewers typically care less about the existence of a bug and more about whether you understand:

* Why it happened
* How to detect it
* How to prevent it from happening again
