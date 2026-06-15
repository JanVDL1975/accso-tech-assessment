# Technical Strategy Memo

**Date:** 2026-06-15
**Status:** Draft - for client engineering lead review
**Audience:** Technical and business decision-makers

---

## 1. Problem Framing and Scope

### What We Are Solving

Courier partners send shipment status updates via webhook. The core problem is that downstream systems receive the same shipment's events at different times, in different orders, and sometimes duplicated - leading to inconsistent views of shipment state. Customer support, order tracking, and incident response all need a trustworthy answer to "what is the current status of this shipment and how do we know?"

This service establishes a **single source of truth** for shipment state, built on an append-only event audit log. The answer must be reliable and explainable.

### What We Are Not Solving

- Customer-facing tracking UI - backend service only
- Real-time push notifications - query-based access only for now
- Courier contract management or billing

### What Good Looks Like

- A single, queryable current state per shipment that reflects all known events
- A complete, append-only event history that can explain how that state was derived
- Deterministic, explainable handling of duplicates, out-of-order arrivals, and conflicting updates

---

## 2. Key Assumptions and Open Questions

### Confirmed via Client Q&A

1. **`occurredAt` is partner-supplied and cannot be trusted as a global clock.** Clock skew between couriers, varying precision, and backfilled timestamps are to be expected. Design for it rather than around it.

2. **`receivedAt` is also partner-supplied.** It represents when the partner first received the event into their system - not when we ingested it. Both timestamps are unreliable for cross-partner ordering.

3. **`eventId` and `shipmentId` are opaque strings owned by the partner.** Formats vary between couriers. Do not assume UUID format, sequential ordering, or cross-partner uniqueness.

4. **The partner is the integration and trust boundary.** Events arrive pre-aggregated via webhook, not pushed directly from driver handhelds. No requirement to validate against courier internal systems.

5. **Events arrive via HTTPS POST webhook.** No push-based streaming protocol required.

### Open Questions for the Client

1. **Shipment ID lifecycle** - can a `shipmentId` be reused after a return? If so, the current-state store needs a reset mechanism.

2. **Out-of-order grace window** - when an older event arrives, do we apply it to state or hold it? A grace window (e.g., 5 minutes) for near-order events vs. strict rejection of anything older than current state.

3. **Duplicate payload handling** - if a retry has the same `eventId` but a different payload, the second is silently rejected. Is payload hash or version tracking needed?

4. **Hosting model and stack** - no house stack is prescribed. We will decide and justify.

---

## 3. Proposed Architecture

### Component Boundaries

```mermaid
graph TD
    P["Courier Partner"]
    IE["Event Ingestion<br/>(webhook endpoint)"]
    EP["Event Processing Pipeline<br/>Normalise → Deduplicate → Resolve"]
    ES["Event Store<br/>(append-only)"]
    CS["Current State Store<br/>(derived, queryable)"]

    P --> IE
    IE --> EP
    EP --> ES
    EP --> CS
```

### Event Processing Flow

```mermaid
flowchart TD
    A["Event arrives via webhook"] --> B["Normalise to canonical model"]
    B --> C{"Is (eventId, partner)<br/>already known?"}
    C -->|YES| D["Reject as duplicate"]
    C -->|NO| E["Load current state for shipment"]
    E --> F["Apply resolution rules engine"]
    F --> G{"Resolution produces<br/>new state?"}
    G -->|YES| H["Append event to store<br/>Upsert current state"]
    G -->|NO| I["Append event to store<br/>State unchanged"]
    H --> J["Record decision in audit trail"]
    I --> J
    D --> J
```

### Where Hard Decisions Live

| Decision | Location | Approach |
|----------|----------|----------|
| Which event wins in a conflict | Resolution rules engine | Deterministic: latest `occurredAt` within allowed transition path; `receivedAt` as tiebreaker |
| Out-of-order handling | Resolution rules engine | Reject events older than current state; document the decision in audit trail |
| Duplicate detection | Deduplication layer | Per-partner `eventId` uniqueness; duplicates logged but do not alter state |
| Terminal state enforcement | Status transition rules | `DELIVERED` and `RETURNED` are terminal - no further events accepted |

---

## 4. Data Integrity Strategy

### Duplicates

- Deduplication is per-partner using `(partner, eventId)` as the uniqueness key
- Duplicate events are logged in the audit trail with a `DUPLICATE` marker
- Duplicate events do not alter current state
- The deduplication window is the lifetime of the shipment record

### Out-of-Order Events

- Events are ordered by `occurredAt` when deriving state
- The system does not assume `occurredAt` is monotonically increasing
- `receivedAt` may be used as a tiebreaker when `occurredAt` is ambiguous
- Events older than the current known state are rejected; events within a grace window are accepted with a flag
- All ordering decisions are recorded in the audit trail

### Conflicting Updates

- A deterministic rules engine resolves conflicts
- Identical event sequences always produce identical state outcomes
- Terminal states (`DELIVERED`, `RETURNED`) cannot be overridden by earlier non-terminal states
- All conflict resolution decisions are logged with their rationale

### Audit Trail Completeness

- The event store is append-only - rows are never updated or deleted
- Every event is stored regardless of whether it updated state
- Every state derivation decision is recorded with its rationale
- The audit trail is queryable and can explain how the current state was derived

---

## 5. Operational Concerns

### Observability

Key signals to track from day one:

- **Duplicate event rate** - a high rate indicates partner retry misconfiguration
- **Rejection rate by reason** - spikes indicate partner payload issues or schema drift
- **Event processing latency** - P99 by partner
- **Current state staleness** - how old is the last update per shipment

### Failure Handling

| Failure Mode | Behaviour |
|-------------|-----------|
| Persistence fails | Transaction rolls back; client receives error; partner retry will succeed |
| Malformed payload | 400 returned immediately; event not stored |
| Resolver encounters invalid state | 500 returned; event not acknowledged; partner retry |
| Unknown shipmentId on first event | Create new shipment record; this is expected for new shipments |

### Ownership Boundaries

| Component | Owner | Notes |
|-----------|-------|-------|
| Event ingestion API | Engineering | Interface contract with partner |
| State resolution rules | Engineering + Product | Business rules; changes require review |
| Partner payload normalisation | Engineering | Partner-specific mapping |
| Metrics and alerting | Operations | Observability stack |

---

## 6. Delivery Plan and Risk Register

The phased delivery plan and risk register are maintained as separate documents:

- **DELIVERY_PLAN.md** - Phase breakdown, minimum credible first slice, success signals
- **RISK_REGISTER.md** - Risks, likelihood/impact assessments, and mitigations

---

## 7. Architectural Decisions to Record (ADRs)

The following decisions warrant formal ADR records:

1. **`occurredAt` as ordering authority with `receivedAt` as tiebreaker** - addresses untrusted partner clocks
2. **Append-only event store with derived current state** - enables auditability and deterministic replay
3. **Per-partner `(partner, eventId)` deduplication key** - handles partner-scoped IDs without requiring global uniqueness
4. **Deterministic, stateless conflict resolution** - identical event sequences produce identical state

---

*This memo reflects the current state of the analysis as of 2026-06-15. It will be updated as open questions are resolved and Phase 1 deliverables are confirmed.*