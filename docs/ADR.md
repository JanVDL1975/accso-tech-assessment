# Architecture Decision Records

**Date:** 2026-06-15

---

## ADR-001: Use `occurredAt` for Event Ordering

**Status:** Accepted

### Context

Courier partners send `occurredAt` timestamps representing when an event happened in their own systems. These timestamps cannot be trusted as a global clock: clock skew between couriers, varying precision, and backfilled timestamps are to be expected.

`receivedAt` is also partner-supplied - it represents when the partner first received the event into their system. Both timestamps are therefore unreliable for cross-partner ordering.

We need a reliable signal to detect out-of-order events and determine which event should win when duplicates or conflicts occur.

### Decision

Use `occurredAt` as the authoritative timestamp for event ordering and out-of-order detection.

When an incoming event's `occurredAt` is before the current state's last `occurredAt`, the event is older than what is already known - it is treated as out-of-order and does not update current state. The event is still recorded in the event store and audit trail for traceability.

`receivedAt` is used as a tiebreaker when `occurredAt` is ambiguous (e.g., two events with identical `occurredAt` values).

### Consequences

**Positive:**
- `occurredAt` reflects the partner's own claim of when the event happened - using it as the ordering authority means the system's state reflects the partner's version of reality
- `receivedAt` as a tiebreaker handles the case where `occurredAt` precision is insufficient (e.g., millisecond-level events from the same source)
- All timestamps are preserved in the audit log so ordering decisions can be reviewed

**Negative:**
- `occurredAt` is partner-supplied and unreliable - clock skew, varying precision, and backfilled timestamps mean the derived order may not reflect true wall-clock order
- A grace window for near-order events is not implemented - events are either accepted or rejected based on timestamp comparison
- Designing for unreliable timestamps rather than around them adds complexity that a trusted clock would avoid

### Alternatives Considered

**Use `receivedAt` as the ordering authority.** Rejected because `receivedAt` reflects when the partner received the event into their system, not when the event actually happened. Using it for ordering would make the system's state depend on the partner's ingestion queue behaviour rather than the actual event timeline.

**Use wall-clock time at ingestion as the ordering signal.** Rejected because it would make the system's ordering dependent on when we received the event, losing the relationship between `occurredAt` and `receivedAt` that is valuable for auditing and debugging.

**Use only `occurredAt` without a tiebreaker.** Rejected. When two events share the same `occurredAt` (which is common at millisecond precision), there is no ordering signal. `receivedAt` as a tiebreaker resolves this without discarding any timestamps.

---

## ADR-002: Append-Only Event Store with Derived Current State

**Status:** Accepted

### Context

We need to answer two questions for any shipment: "what is the current status?" and "how do we know?". Customer support and incident response need to trust the answer and understand its derivation.

Simply overwriting a status field on each event would lose history. Replaying all events on every query would be correct but potentially slow for high-volume shipments.

### Decision

Maintain two separate data structures:

1. **Append-only event store** - every accepted event is written once and never modified or deleted. This is the source of truth for event history and enables deterministic replay.

2. **Derived current state** - a single view per shipment that is updated whenever an accepted event produces a new status. This is the fast path for status queries.

The current state can be rebuilt at any time by replaying the event store for a shipment.

### Consequences

**Positive:**
- Full event history is always available for auditing, debugging, and reprocessing
- Status queries are fast (single-record lookup) rather than requiring event replay
- Append-only storage is safe - historical records are never modified
- If resolution logic changes, events can be reprocessed from history to derive corrected state

**Negative:**
- Two writes per accepted event instead of one - slight increase in write amplification
- The derived current state is a cache that could theoretically drift from what replay would produce; monitoring is needed to detect this
- Schema evolution requires careful migration strategy

### Alternatives Considered

**Replay all events on every query.** Rejected because this would be too slow for shipments with long event histories.

**Overwrite status on every event.** Rejected because this loses the event history needed for auditing and debugging, and makes reprocessing impossible if logic changes.

**Append-only store with periodic snapshots.** Deferred. Snapshots could optimise replay for long-running shipments but are not needed in Phase 1.

---

## ADR-003: Per-Partner `(partner, eventId)` as Deduplication Key

**Status:** Accepted

### Context

Event IDs are owned by the courier partner. The client confirmed that `eventId` is not globally unique - formats vary between couriers, and the same `eventId` value could exist in multiple partners' systems.

Partners deliver events via webhook on an at-least-once basis. Duplicates are expected and must be handled idempotently.

### Decision

Deduplication is scoped to the partner using `(partner, eventId)` as the uniqueness key.

When an event arrives, the system checks whether an event with the same `(eventId, partner)` has already been processed. If so, the event is rejected as a duplicate. The duplicate is still recorded in the audit trail for traceability.

### Consequences

**Positive:**
- Deduplication is per-partner, correctly handling the case where the same `eventId` value exists in multiple partners' systems
- Duplicate events are logged, so the fact of the duplicate is not lost
- Idempotent: re-submitting the same event produces the same outcome every time

**Negative:**
- If a partner retries with the same `eventId` but a different payload (e.g., a corrected status), the second submission is silently rejected
- The deduplication window is the lifetime of the shipment record - there is no time-bounded deduplication

### Alternatives Considered

**Use `(eventId, partner, payloadHash)` as the deduplication key.** Deferred. This would detect retries with corrected payloads but adds complexity. Sufficient for Phase 1 to note this as a known gap.

**Global deduplication key across all partners.** Rejected because the client confirmed `eventId` is not globally unique across partners.

**Time-bounded deduplication.** Rejected because the deduplication window should be the lifetime of the shipment record - a retry months later for an active shipment should still be detected as a duplicate.

---

## ADR-004: Deterministic, Stateless Conflict Resolution

**Status:** Accepted

### Context

When multiple events arrive for the same shipment, they may conflict: arrive out of order, represent invalid transitions, or represent competing updates that must be arbitrated. The outcome must be deterministic - the same sequence of events must always produce the same final state - and explainable.

### Decision

A resolution engine evaluates each incoming event against the current known state. The engine is stateless: it takes only the incoming event and the current state as inputs, and produces a deterministic result.

The default rules applied in order are:

1. **No current state** - any starting status is valid; accept.
2. **Out-of-order** - if the incoming `occurredAt` is before the current state's last `occurredAt`, the event does not update state.
3. **Invalid transition** - if the status transition is not in the allowed set, reject.
4. **Valid transition** - accept and update state to the incoming status.

Every resolution decision is recorded in the audit trail with the decision (accepted / no-update / rejected), previous status, new status, and rejection reason if any.

### Consequences

**Positive:**
- Deterministic: identical inputs always produce identical outputs - replay is safe and debugging is straightforward
- Stateless: the resolver has no dependencies and can be tested in isolation
- Pluggable: a custom resolver can be injected to handle courier-specific rules without modifying the core pipeline
- Audit-friendly: every decision is recorded with reasoning

**Negative:**
- The resolver cannot consider cross-event context beyond the current state row - rules that require scanning event history would need a different architecture
- Terminal states (`DELIVERED`, `RETURNED`) block all further updates - correcting an error on a delivered shipment requires a separate mechanism

### Alternatives Considered

**Stateful resolver with access to event history.** Rejected for Phase 1. Harder to test, harder to reason about, and could introduce non-determinism.

**Externalised rules engine.** Deferred. Adds significant complexity that is not justified by the current requirements.

---

*ADRs should be updated whenever a decision changes.*