# INTERVIEW_PREPARATION_SIM.md

# Shipment Integrity Service — Interview Preparation

Consolidated from R1, R2, and R3.

---

# Part 1: Introduction & System Overview

---

## Q1. Can you walk me through your role in this system and explain what core problem you were trying to solve?

### Strong Answer

I was acting as a consulting software engineer responsible for designing and implementing a Shipment Integrity Service for an e-commerce platform.

The business problem was that courier partners emit shipment events that are often unreliable. Events can arrive late, out of order, duplicated, or contain conflicting information. Downstream systems such as customer support, order tracking, and incident management were independently deriving shipment state and frequently disagreed about a shipment's actual status.

My role involved both technical implementation and requirements clarification. Several aspects of the problem were underspecified, including the deduplication strategy, authoritative timestamp selection, and batch processing behaviour. I worked with the client to resolve these ambiguities and document decisions through ADRs.

The resulting service provides a single source of truth. It ingests courier events, deduplicates them using `(partner, eventId)`, resolves authoritative shipment state using `receivedAt`, stores a complete audit trail, and exposes APIs so downstream systems no longer need to derive shipment status themselves.

The objective: every consumer receives the same answer, derived using the same logic, every time.

---

## Q2. Can you describe the system you designed?

### Strong Answer

The system is a **Shipment Integrity Service** that ingests shipment events from multiple courier partners via webhooks and maintains a **consistent, trustworthy view of shipment state**.

The core problem it solves is that courier events are unreliable in practice:
- They can arrive **out of order**
- They can be **duplicated**
- They can be **delayed**
- Sometimes they can be **conflicting across partners**

The system ensures that despite this, we always maintain the **correct latest authoritative state of a shipment**.

At a high level, the system:
1. Receives webhook events from courier partners
2. Validates and deduplicates them using `(partner, eventId)`
3. Applies ordering rules using `receivedAt`
4. Resolves correct shipment state using deterministic rules
5. Persists raw events, derived events, and audit decisions
6. Exposes APIs so downstream systems can query shipment status reliably

The main design goal is **correctness under unreliable input**, not just event storage.

---

## Q3. What problem are you solving and why is it difficult?

### Strong Answer

The core problem is maintaining **state consistency in a distributed, event-driven system where inputs are unreliable**.

It is difficult because courier events are:
- **At-least-once delivered** → duplicates are expected
- **Not ordered** → IN_TRANSIT may arrive before LABEL_CREATED
- **Not fully reliable** → some events may be missing or delayed
- **Multi-source** → different partners may report different states for the same shipment

The challenge is not storing events, but answering:

> "Given messy, unordered events, what is the correct current shipment state?"

This requires idempotency, deduplication, an ordering strategy, conflict resolution logic, and a deterministic state machine.

---

## Q4. What does an incoming event look like?

### Strong Answer

Each incoming webhook event includes:

- `partner` (e.g., DHL, FedEx)
- `eventId` (unique per partner event)
- `shipmentId`
- `eventType` (e.g., LABEL_CREATED, IN_TRANSIT, DELIVERED)
- `occurredAt` (when the courier says it happened)
- `receivedAt` (when our system received it)
- Optional metadata (location, scan details, etc.)

The most important fields for correctness:
- `partner + eventId` → for deduplication
- `receivedAt` → for ordering decisions (authoritative per ADR-001)
- `eventType` → for state transitions

---

## Q5. What does your data model look like?

### Strong Answer

Three persisted stores, each with a distinct purpose:

### 1. Raw Events (append-only)

Stores exact partner input for audit and replay:
- `partner`, `eventId`, `shipmentId`, `eventType`, `occurredAt`, `receivedAt`, `payload`

### 2. Derived Events (append-only)

Stores canonical business transitions:
- Derived from raw events after deduplication and ordering validation
- Drives the current state

### 3. Current State (mutable)

Stores the latest authoritative state per shipment:
- `shipmentId`, `currentState`, `lastReceivedAt`, `lastOccurredAt`

This separation enables:
- Full audit history (raw events)
- Fast O(1) reads (current state)
- Replay and recomputation (derived events)

---

# Part 2: Core Processing Logic

---

## Q6. How does your system ensure it produces a correct shipment state despite duplicate, late, or out-of-order events?

### Strong Answer

The system follows a deterministic processing pipeline.

**1. Deduplication**
Events are deduplicated using `(partner, eventId)` — the composite key is required because event IDs are only unique within a partner, not globally.

**2. Ordering**
Events are ordered using `receivedAt`, which ADR-001 defines as authoritative. `occurredAt` is stored for audit but not used for ordering because it is unreliable for partners who backfill events.

**3. Out-of-order handling**
An event with a `receivedAt` older than the current state's last `receivedAt` is stored but does not update the current state. It is marked as out-of-order and recorded in the audit log.

**4. State resolution**
If the event is newer, it is passed to the `ShipmentStateResolver`, which validates the transition against the shipment state machine. Invalid transitions are rejected from state updates but recorded in the audit log.

**5. Persistence**
Valid transitions result in: derived event creation, current state update, and audit logging.

All events are retained, all decisions are auditable, and state changes occur only through valid transitions.

---

## Q7. How do you handle duplicate events?

### Strong Answer

We handle duplicates using idempotency at the event level.

The system stores a unique constraint on `(partner, eventId)`. When a new event arrives:
- We check if this key already exists in the event store
- If it exists → we reject it with `reason=DUPLICATE_EVENT`
- If not → we persist it and process it

This ensures that even if a courier retries the same webhook multiple times, it has no effect on system correctness or state transitions. This is critical because webhook systems are typically at-least-once delivery systems.

---

## Q8. How do you deal with out-of-order events?

### Strong Answer

Out-of-order events are handled using a deterministic ordering strategy, not arrival order.

We do NOT assume events arrive in sequence. We evaluate ordering using `receivedAt` (per ADR-001).

We apply a rule:

> Only allow state transitions that move forward in time.

We enforce a **monotonic state update rule**:
- A shipment cannot move backward in lifecycle (e.g., DELIVERED → IN_TRANSIT is ignored)
- Older events cannot override newer valid states

Late-arriving events do not corrupt the current shipment state — they are stored and audited but do not update the derived state.

---

## Q9. How do you decide the "correct" shipment state?

### Strong Answer

We use a combination of:
1. **Finite state machine (FSM)** defining valid transitions
2. **Event ordering via `receivedAt`**
3. **Authoritative timestamp comparison**

The FSM defines valid transitions:
- LABEL_CREATED → HANDED_TO_CARRIER → IN_TRANSIT → OUT_FOR_DELIVERY / DELIVERY_EXCEPTION → DELIVERED / RETURNED

When an event arrives:
- We filter invalid transitions
- We sort remaining candidates by `receivedAt`
- We apply the latest valid state transition

The goal is a system that is deterministic, repeatable, and explainable.

---

## Q10. What consistency model does your system follow?

### Strong Answer

The system follows **eventual consistency with strong local determinism**.

- Event ingestion is asynchronous
- Events may arrive in any order
- Temporary inconsistencies are possible
- But the system guarantees that: given the same set of events, the final state will always be the same

So: not strictly consistent in real-time, but **deterministically consistent over time**.

---

## Q11. How do you ensure consistency between event processing and state updates?

### Strong Answer

We ensure consistency using **atomic processing per event** — each event processing step is wrapped in a single database transaction:

1. Check deduplication key
2. Persist event (if new)
3. Compute new state
4. Compare against current stored state
5. Update shipment state if valid

We also ensure:
- Optimistic locking on shipment state (to avoid race conditions)
- Idempotent updates (reprocessing same event produces no change)

---

# Part 3: Key Architecture Decisions

---

## Q12. Why did you choose `receivedAt` over `occurredAt`?

### Strong Answer

We chose `receivedAt` because it is the most trustworthy timestamp available.

The client confirmed that some courier partners, particularly Partner B, backfill or generate unreliable `occurredAt` timestamps due to clock skew, delayed processing, or operational workflows. When Partner B accumulates events over hours and sends them as a batch, `occurredAt` values can be hours earlier than `receivedAt`, making ordering completely wrong for entire batch windows.

`receivedAt` represents when the partner's own system received the event. It preserves the partner's chronological sequence without our network artifacts.

If `receivedAt` were wrong, the consequences would be significant:
- Events processed in the wrong sequence
- Incorrect shipment state
- Invalid transitions accepted, valid transitions ignored
- Inconsistent downstream views of shipment status

The entire correctness model depends on a reliable ordering signal, which is why ADR-001 explicitly documents this decision.

---

## Q13. Why not Kafka?

### Strong Answer

Kafka is an excellent technology, but it was deliberately excluded from Phase 1.

The system receives webhook calls and processes them synchronously. There is only one consumer of the events and no requirement for high-volume stream processing.

Kafka would increase:
- Operational complexity (cluster management, topic configuration)
- Deployment complexity
- Monitoring requirements

without solving a current problem.

Kafka makes sense when: multiple independent consumers process events differently, genuine async processing with replay is required, or volume demands partitioning. None of those were Phase 1 concerns.

---

## Q14. Why did you choose SQLite and when would you migrate to PostgreSQL?

### Strong Answer

SQLite was chosen because it offers low operational complexity and is entirely sufficient for the Phase 1 workload.

The primary limitation is its single-writer model. As sustained write throughput increases, lock contention becomes a bottleneck.

I would consider migration when:
- Sustained writes approach 500–1000 writes/sec
- Multiple courier partners significantly increase event volume
- Write latency begins violating SLOs
- Lock contention becomes visible

The migration would be incremental:
1. Introduce PostgreSQL alongside SQLite
2. Enable dual-write through feature flags
3. Run reconciliation checks to validate consistency
4. Migrate historical data
5. Cut over reads and writes
6. Remove SQLite

What changes: connection pool configuration, SQL dialect, database infrastructure.
What remains the same: domain model, resolver logic, deduplication strategy, event processing pipeline, repository interfaces.

The repository abstraction isolates business logic from persistence, making migration relatively low risk.

---

## Q15. Why didn't you implement full Event Sourcing?

### Strong Answer

The architecture borrows event sourcing concepts but stops short of full event sourcing.

Full event sourcing requires rebuilding shipment state from the entire event stream on every query or maintaining projections. Our primary read use case is retrieving the current shipment status quickly.

By maintaining append-only event history alongside derived current state, we get:
- O(1) status lookups
- Full audit history
- Replay capability

while avoiding the complexity of a fully event-sourced architecture.

---

## Q16. Why split raw_events, derived_events, and audit_log?

### Strong Answer

Each store has a distinct purpose:

| Store | Purpose |
|-------|---------|
| **Raw events** | Exact partner input — preserved for legal compliance and replay |
| **Derived events** | Canonical business transitions — drives current state |
| **Audit log** | Reasoning behind every state decision — for traceability and debugging |

This separation improves clarity, maintainability, and compliance. It also allows independent retention policies: raw payloads deleted after 30 days, audit decisions after 1 year, derived events retained indefinitely.

---

# Part 4: The receivedAt Bug

---

## Q17. The ADR specified `receivedAt` but the code still used `occurredAt`. What happened?

### Strong Answer

ADR-001 defined `receivedAt` as authoritative, anticipating Partner B's needs when the change request introduced them. However, the resolver code was not updated at the same time — it still partially relied on `occurredAt`.

The tests were written with both timestamps set to identical values, so they passed regardless of which timestamp the code actually checked. The tests verified that older events don't update state and newer events do, but they never exercised the difference between `receivedAt` and `occurredAt`.

---

## Q18. How did this slip through if you had tests?

### Strong Answer

The tests covered the happy path but did not distinguish between `receivedAt` and `occurredAt` ordering. With both timestamps identical in the test data, the code under test could use either one and the tests would pass.

A test with divergent timestamps — `occurredAt` earlier than `receivedAt`, or vice versa — would have caught this immediately. No such test existed.

---

## Q19. How would you detect this bug if it reached production?

### Strong Answer

The first indicators would be correctness metrics:
- Increased out-of-order rates
- Elevated transition failures
- State reconciliation mismatches
- Customer reports of inconsistent shipment state

The most reliable detection mechanism is a reconciliation process that periodically rebuilds shipment state from raw events and compares the result with the stored derived state.

Because the system retains all raw events, we can replay history, identify affected shipments, and rebuild state after the fix is deployed.

---

## Q20. What would you do to prevent this happening again?

### Strong Answer

**1. ADR-to-code traceability**
Critical architectural decisions should be linked to automated tests. If ADR-001 defines ordering behaviour, there should be tests explicitly validating that behaviour.

**2. Timestamp divergence test fixtures**
Tests should intentionally use different values for `occurredAt` and `receivedAt` to verify ordering logic.

**3. Integration testing**
End-to-end event sequences should be tested using realistic out-of-order scenarios.

**4. Regression tests**
A dedicated regression test should reproduce the exact bug scenario to ensure it never reappears.

**5. AI-assisted ADR checking**
Prompts that generate resolver code should include the relevant ADRs as context.

The lesson: important architectural decisions should be executable and verifiable, not just documented.

---

# Part 5: Production & Operational Excellence

---

## Q21. What would you monitor first in production?

### Strong Answer

I would focus on three categories.

**Correctness** — the most important:
- Out-of-order rate
- Transition failure rate
- Idempotency conflict rate
- Derived state reconciliation drift

**Throughput**:
- Event ingestion rate per partner
- Database write throughput
- Request failure rate

**Latency**:
- Ingestion latency
- State resolution latency
- End-to-end processing latency

Together these provide visibility into both operational health and business correctness.

---

## Q22. If you were taking this from thin-slice to production-grade, what would you add first?

### Strong Answer

**Phase 1: Observability**
- Metrics, distributed tracing, structured logging, alerting

Without observability, operational issues are discovered by customers rather than engineers.

**Phase 2: Correctness Hardening**
- ADR-to-code validation tests
- Timestamp divergence test suites
- Integration testing
- Replay validation tests

This addresses the class of bugs that caused the `receivedAt`/`occurredAt` mismatch.

**Phase 3: Operational Maturity**
- Correction event API
- PostgreSQL migration readiness
- Capacity planning
- Incident response runbooks

---

## Q23. How would you support corrections to terminal shipment states?

### Strong Answer

The current design intentionally uses an append-only model. Directly modifying shipment state would violate auditability.

I would introduce a dedicated correction event containing:
- Original state
- Corrected state
- Reason
- Requestor
- Approver
- Timestamp

Rather than modifying existing records, the correction is appended to the event stream. This preserves append-only guarantees, auditability, and replayability while supporting real-world correction workflows.

Correction events would be kept separate from the shipment state machine because they represent administrative actions rather than courier lifecycle transitions.

---

## Q24. How would you detect and recover from state drift after a crash?

### Strong Answer

If the service crashes after writing to the event store but before updating current state, the event is persisted but the state is not updated. On restart, the shipment shows the wrong state until another event triggers recomputation.

Detection: the reconciliation process rebuilds state from raw events and compares against stored current state. Any mismatch triggers an alert.

Recovery: the event store is append-only. For any shipment where state is wrong, we replay events in correct `receivedAt` order and recompute state. The resolver is deterministic — same inputs always produce same outputs. The fix is: correct the resolver, then run a replay job for affected shipments. No data is lost.

---

# Part 6: Closing Summary

---

## Key Architectural Principles

| Decision | Rationale |
|----------|-----------|
| `receivedAt` ordering | `occurredAt` is unreliable for partners who backfill |
| `(partner, eventId)` deduplication | Event IDs are only unique within a partner |
| Append-only event store | Enables audit, replay, and compliance |
| Derived current state | O(1) reads without replaying history |
| Per-event atomic processing | Consistency under concurrency |
| Repository abstraction | Isolates business logic from persistence |
| Resolver interface | Allows partner-specific logic without changing core |

---

## Biggest Production Risks

1. Incorrect ordering logic (the receivedAt bug — now caught)
2. Out-of-order partner behaviour (especially Partner B's batch backfill)
3. Missing correction mechanism for terminal states
4. SQLite write contention under multi-partner load
5. Lack of observability masking silent failures

---

## Key Themes to Reinforce in Interview

**Architecture**
- Append-only event store with derived current state
- Replay capability and audit-first design
- API-centric synchronous processing (not Kafka)

**Correctness**
- `receivedAt` ordering per ADR-001
- Deduplication via `(partner, eventId)`
- State-machine validation with permissive transitions
- Out-of-order event handling

**Production Readiness**
- Metrics and observability model
- Replay and reconciliation
- Incident response runbooks
- PostgreSQL migration path

**Ownership**
- Acknowledge the receivedAt/ADR mismatch bug directly
- Explain how it happened (identical timestamps in tests)
- Explain the process improvements to prevent recurrence
- Senior interviewers care more about your response to the bug than the fact it existed

---

## Anti-Patterns to Avoid

| Instead of | Say |
|------------|-----|
| "SQLite serializes writes" | "I evaluate three signals: write contention, latency under load, projected scale" |
| "First event must be LABEL_CREATED" | "The system enforces four invariants: deduplication, ordering, auditability, valid transitions" |
| "I would add metrics" | "The current risk is silent state drift. The mitigation is observability, tracing, and validation tests" |
| "Event sourcing would solve this" | "Event sourcing adds replay complexity we don't need — O(1) reads with append-only audit is the right tradeoff for our access pattern" |
