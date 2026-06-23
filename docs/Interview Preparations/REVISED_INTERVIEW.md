# REVISED_INTERVIEW.md

---

# 1. 5-Minute Interview Cheat Sheet

## System Summary (30 seconds)

The Shipment Integrity Service ensures a trustworthy shipment state despite duplicate, delayed, out-of-order, or conflicting courier events.

It processes webhook events, deduplicates them using `(partner, eventId)`, resolves authoritative state using `receivedAt`, stores raw events, derived events, and audit decisions, and exposes APIs for current status, history, and auditability.

The goal is to maintain a single reliable shipment state even when upstream data is inconsistent.

---

## Core Architecture Decisions

| Decision | Why |
|----------|-----|
| `receivedAt` ordering | `occurredAt` is unreliable across partners |
| Append-only + derived state | Enables replay + fast reads |
| `(partner, eventId)` deduplication | `eventId` is not globally unique |
| Per-event batch isolation | Prevents poison events |
| Resolver interface | Allows partner-specific logic |

---

## Known Bug (Critical Learning)

### What happened?
ADR-001 specified `receivedAt` ordering, but implementation still partially relied on `occurredAt`.

### Why it was missed?
Tests used identical timestamps, so ordering issues were never exposed.

### Prevention
- Divergent timestamp test fixtures  
- ADR → code traceability checks  
- Ordering validation tests  

---

# 2. Communication Frameworks

## Architecture Questions

**Decision → Reason → Trade-off → Recommendation**

### Example

Why dual-store?

I chose a dual-store approach because it balances performance and recoverability.

It provides O(1) reads for current state while preserving full replay capability.

Trade-off is potential divergence between raw and derived views.

Given the read-heavy nature, this is the right compromise.

---

## Bug Questions

**What happened → Why missed → Prevention**

### Example

ReceivedAt bug

The system used `occurredAt` in parts of the pipeline despite ADR-001 specifying `receivedAt`.

It was missed due to identical timestamps in tests.

Prevention requires divergence-based test data and stricter ADR alignment validation.

---

## Production Questions

**Current Behaviour → Risk → Mitigation**

### Example

Clock drift

Current system relies on `receivedAt` ordering.

Clock drift may lead to incorrect sequencing and stale shipment state.

Mitigation includes skew detection metrics, alerting, and eventually a bounded grace window.

---

## Leadership Questions

**Acknowledge → Trade-off → Recommendation**

### Example

Why keep raw events?

We could simplify the system by removing raw events.

However, we would lose auditability, replay capability, and compliance traceability.

I would retain raw events and simplify the resolver instead.

---

# 3. Interview Questions & Strong Answers

---

## Walk me through the system

### 30-second answer

The system ensures reliable shipment state despite inconsistent courier events.

It ingests webhook events, deduplicates them using `(partner, eventId)`, resolves ordering using `receivedAt`, persists raw and derived events, and exposes APIs for status, history, and audit logs.

---

### 2-minute answer

The system has four stages:

1. **Ingestion**
   - Webhook events received from courier partners

2. **Deduplication**
   - Uses `(partner, eventId)` to ensure idempotency

3. **Resolution**
   - Orders events using `receivedAt`
   - Produces canonical shipment state via resolver

4. **Persistence & Query**
   - Raw events stored for audit/replay
   - Derived events represent business state
   - Current state stored for O(1) reads

This design ensures consistency despite duplicates, delays, and out-of-order delivery.

---

## Why not Event Sourcing?

### 30-second answer

Event sourcing was rejected because the system prioritises current-state queries over full event reconstruction.

---

### 2-minute answer

Event sourcing provides full historical fidelity but requires reconstructing state on every query, which is O(n).

This system optimises for current shipment status, which is the dominant access pattern.

We therefore use a hybrid model:
- Raw events for replay
- Derived state for fast reads

Trade-off: less pure event-sourcing model, but significantly better performance.

---

## Why not Kafka?

### 30-second answer

Kafka is unnecessary because there is a single consumer and a single processing pipeline.

---

### 2-minute answer

Kafka is designed for distributed streaming with multiple independent consumers.

In this system:
- One ingestion pipeline
- One authoritative processor
- No downstream consumers

Kafka would introduce operational overhead without immediate benefit.

It remains a viable future migration option if the system evolves.

---

## Why split raw_events, derived_events, audit_log?

### 30-second answer

They separate ingestion, business state, and decision traceability.

---

### 2-minute answer

Each store has a distinct purpose:

- **Raw events** → exact partner input (audit + replay)
- **Derived events** → canonical business transitions
- **Audit log** → reasoning behind state decisions

This separation improves clarity, maintainability, and compliance.

---

## What invariants does the system enforce?

### 30-second answer

Four invariants: deduplication, ordering, auditability, and valid state transitions.

---

### 2-minute answer

The system enforces:

1. Deduplication via `(partner, eventId)`
2. Ordering via `receivedAt`
3. Auditability via decision logs
4. Valid state transitions via state rules

The model is intentionally permissive, validating observed transitions rather than enforcing strict workflows.

---

## When would you migrate from SQLite?

### 30-second answer

When write contention, latency, or scaling constraints exceed a single-writer model.

---

### 2-minute answer

Migration triggers:

- Sustained write throughput pressure
- Lock contention causing latency spikes
- Growth projections exceeding single-node limits

SQLite is suitable for early simplicity, but PostgreSQL becomes necessary for scale.

---

## How would you implement corrections?

### 30-second answer

Corrections should be implemented as explicit correction events, not direct updates.

---

### 2-minute answer

Instead of modifying stored data, introduce a correction event API.

This preserves:
- Append-only integrity
- Auditability
- Full history traceability

Direct updates would violate system guarantees.

---

# 4. Deep-Dive Follow-Ups

## Key assumptions

- Partner event IDs are stable across retries  
- `receivedAt` is more reliable than `occurredAt`  
- Retry windows exceed outage windows  
- Current-state queries dominate usage  

---

## Production risks

- No observability layer (metrics/tracing)  
- SQLite write bottleneck  
- No correction mechanism  
- Unknown partner ordering behaviour  

---

## If given one week before production

- Add observability (metrics + tracing)
- Add ADR-to-code validation tests
- Add timestamp divergence test suite
- Introduce correction event API
- Prepare PostgreSQL migration path

---

# 5. Interview Drill Questions

## Walk me through the system
Use full structured answer (30s + 2m)

---

## Why dual-store?

I chose a dual-store model because it balances performance and auditability.

It enables O(1) reads for current state while preserving full replay capability.

Trade-off is potential divergence, which is controlled through deterministic replay rules.

---

## Explain the receivedAt bug

The bug occurred because ADR-001 specified `receivedAt`, but parts of the implementation still used `occurredAt`.

It was missed due to identical timestamps in test data.

Prevention requires divergent test cases and strict ADR alignment validation.

---

## What AI gets wrong in system design

AI often optimises components in isolation but misses system invariants like ordering, idempotency, and failure handling.

This system explicitly encodes those invariants in deterministic rules and audit logs.

---

## What would you do with one more week?

- Observability (metrics + tracing)
- Stronger regression tests for ordering
- Correction event API
- ADR-to-code validation improvements

---

# 6. Common Failure Patterns

## Instead of

“SQLite serializes writes…”

## Say

I evaluate three signals before considering migration:
- write contention  
- latency under load  
- projected scale  

---

## Instead of

“First event must be label created…”

## Say

The system enforces four invariants: deduplication, ordering, auditability, and valid transitions.

---

## Instead of

“I would add metrics…”

## Say

The current risk is silent state drift. The mitigation is observability, tracing, and validation tests.

---

# End of Document