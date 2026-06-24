# Architecture Rationale

This document explains the *why* behind the key design decisions in the Shipment Integrity Service. It is a companion to `ARCHITECTURE.md` — that document describes *what* the architecture is; this document describes *why* it is that way and what alternatives were considered.

This document is ordered chronologically: pre-change request architecture first, then post-change request decisions. This reflects how the architecture actually evolved — decisions build on what came before.

---

## Pre-Change Request Architecture

The mandatory change request introduced batch ingestion, legal retention, and Partner B preparation. The original architecture reflected Phase 1 scope: a single append-only event store, Partner A only, `occurredAt` as ordering default.

### What the original architecture looked like

| Aspect | Pre-Change Request |
|--------|-------------------|
| **Event store** | Single append-only event store |
| **Audit trail** | Embedded in event store or minimal |
| **Scheduling** | `@EnableScheduling` removed (not needed in Phase 1) |
| **Ordering** | `occurredAt` as default — Phase 1 scoped to Partner A, where this was a reasonable default |
| **API** | Separate endpoints for single and batch events |
| **Retention** | Not specified |
| **Partner B** | Not in scope — deferred to Phase 2 |

### Why a single event store was sufficient originally

Phase 1 had no legal retention requirements and no separate audit log requirement. A single append-only store served both purposes — it provided audit and replay, and there was no need to apply different retention policies to different data types.

### Why `occurredAt` was the original default

Phase 1 was scoped to Partner A only. Partner A's events were not subject to the backfill problem that made `occurredAt` unreliable for Partner B. Using `occurredAt` was a reasonable default for the Phase 1 scope.

### Why two endpoints for single and batch

The original design had separate endpoints because the processing paths were expected to differ. Batch ingestion was a future Phase 2 concern — keeping the paths separate avoided introducing complexity before it was needed.

### Why scheduling was removed

`@EnableScheduling` was removed as part of cleaning up unused infrastructure. Nothing in Phase 1 required scheduled jobs. This was correct at the time.

---

## Post-Change Request Architecture

The mandatory change request changed several architectural decisions. The sections below document the rationale for each change, and the new decisions that applied only after the change request.

---

## 1. Ordering Authority: receivedAt over occurredAt

### Decision

`receivedAt` is the authoritative timestamp for event ordering. `occurredAt` is stored for audit but not used for ordering.

### Why receivedAt

Three options were evaluated:

**1. occurredAt — rejected**
The event's actual timestamp as reported by the courier. Rejected because some partners — particularly Partner B — backfill events. When Partner B accumulates events over hours and sends them as a batch, `occurredAt` values can be hours earlier than when the batch was actually emitted. This makes `occurredAt` actively unreliable for ordering: a delayed event could appear older than a genuine newer event, causing state regressions.

**2. Our own ingestion timestamp — rejected**
Wall-clock time when our system receives the event. Rejected because it severs the link to the partner's own timeline. If Partner B sends batch 1 at 09:00 and batch 2 at 17:00, but batch 2 arrives before batch 1 due to network timing, our own clock would order them backwards — introducing our own artifacts into the partner's ordering logic.

**3. receivedAt — chosen**
The partner's own ingestion timestamp — when the partner's system received the event. This preserves the partner's chronological sequence without our network artifacts. Not perfect — Partner B's clock can still drift — but far more stable than `occurredAt` for the backfill problem.

### What "older" means

"Older" means a timestamp that is less recent — an earlier point in time. If the current state has `lastReceivedAt = 12:00` and a new event has `receivedAt = 11:30`, the event is older. The system checks: `event.receivedAt < currentState.lastReceivedAt` — if true, the event is stored but does not update current state. No waiting, no grace window, no speculation.

---

## 2. The State Machine: Sieve, Not Shield

### Decision

The state machine validates that a transition is legally valid — it does not validate that a transition is temporally correct.

### Why permissive

Two design choices:

**First event is not required to be LABEL_CREATED.**
Courier partners do not always send the label creation event. Enforcing `LABEL_CREATED` as mandatory first would block valid shipments from being tracked. When there is no current state, any incoming status is accepted as valid.

**Intermediate states are not required.**
A shipment can skip intermediate states as long as the path is valid. `LABEL_CREATED → IN_TRANSIT` is permitted even if `HANDED_TO_CARRIER` was never sent. The system does not infer missing states — it simply accepts that the current state is whatever the most recent valid event says it is.

### Why it doesn't prevent clock skew damage

The state machine validates structure, not sequence. If an event's `receivedAt` is skewed into the future and triggers a valid transition (e.g., `IN_TRANSIT → OUT_FOR_DELIVERY`), the state machine accepts it because the transition is structurally correct. It cannot distinguish between a legitimately newer `OUT_FOR_DELIVERY` and a skewed one that happened earlier but got a timestamp that looks newer.

This is why the ordering assumption is load-bearing: the entire correctness model depends on `receivedAt` being trustworthy. The state machine is a sieve — it catches nonsense transitions — but it cannot catch temporally incorrect ones.

---

## 3. receivedAt Clock Skew

### The assumption

`receivedAt` is treated as trustworthy and monotonically increasing per partner. This holds under normal conditions but breaks under clock skew.

### What clock skew is

Clock skew occurs when a partner's server clock drifts from true time — ahead (running fast) or behind (running slow). Since `receivedAt` is set by the partner's system, we inherit that drift.

Skew is bounded in normal conditions but can be significant in edge cases: NTP misconfiguration, virtual machine clock drift, batch jobs running off-hours, or Partner B's batch model where all events in a batch share the same skew window.

### Failure mode: future-dated events

An event arrives with a `receivedAt` that is in the future relative to other events. The system compares timestamps lexicographically — it has no mechanism to detect that a timestamp is implausible.

Walk-through:
1. Current state: `lastReceivedAt = 12:00`
2. Event A: `receivedAt = 12:05` — legitimate, state updated
3. Current state: `lastReceivedAt = 12:05`
4. Event B: `receivedAt = 12:08` — but Partner B's clock was fast, and `occurredAt = 11:55` (genuinely older)
5. System checks: `12:08 > 12:05`? Yes — state is updated
6. Result: the genuinely older event overwrote correct state

The state machine cannot prevent this because `IN_TRANSIT → OUT_FOR_DELIVERY` is a valid transition. The wrongness is silent — no exception, no error log entry, just wrong state.

### Failure mode: back-dated events

An event arrives with `receivedAt` significantly in the past (partner clock running slow). The system correctly rejects it as older, but the rejection is correct by accident. Valid, genuinely newer events could be incorrectly rejected if their timestamps also carry slow skew.

### Mitigations

| Mitigation | How it helps | Limitation |
|------------|--------------|------------|
| Bounded skew detection | Flag events with `receivedAt` far from our wall clock | Only catches extreme skew; needs a threshold |
| Reconciliation job | Detects state drift after the fact | Reactive, not preventive |
| Grace window | Holds borderline events briefly | Does not fix skew; delays processing; threshold is arbitrary |
| `occurredAt` cross-check | Flag large gaps between `receivedAt` and `occurredAt` | Only indicates skew, doesn't resolve it |

None prevent the wrong state from being written. They only increase the chance of detecting it.

---

## 4. Three Stores: raw_events, derived_events, audit_log

### Decision

Three separate stores, each with a distinct purpose and independent retention policy.

| Store | Purpose | Retention |
|-------|---------|-----------|
| **raw_events** | Exact partner input — preserved for legal compliance and replay | 30 days |
| **derived_events** | Canonical business transitions — drives current state | Indefinite |
| **audit_log** | Reasoning behind every state decision | 1 year |

### Why not one store?

One store would conflate three different access patterns:
- Raw events are evidence — needed for disputes, partner debugging, legal hold
- Derived events are state — needed for fast queries and recomputation
- Audit decisions are reasoning — needed to understand why the system made a specific decision

Mixing them makes retention policy impossible: you can't delete raw data at 30 days if it's mixed with derived state you want to keep indefinitely.

### Why the three-store model replaced one

The original single append-only store conflated raw evidence (needed for disputes and partner debugging) and derived state (needed for queries and recomputation). A single retention policy couldn't serve both.

### Why scheduling was re-added

`@EnableScheduling` had been removed as part of cleaning up unused infrastructure. It was re-added specifically to support the daily retention cleanup jobs. This was a one-line change but represents a class of risk — infrastructure annotations removed as part of cleanup can accidentally disable scheduled work that has since been added.

---

## 5. Deduplication: Against raw_events, Not a Separate Table

### Decision

Deduplication is checked against `raw_events` using `(partner, eventId)`. A database unique constraint on `(event_id, partner)` is the hard backstop.

### Why against raw_events

The unique constraint lives on `raw_events` because:
- `raw_events` is the first thing written — before any decision is made
- Every event that passes deduplication is permanently stored, keeping the record of what arrived complete
- The API returns `reason=DUPLICATE_EVENT` before any processing happens

Duplicates are **not stored**. The service check returns `existsByEventIdAndPartner` — if true, the event is rejected immediately. `raw_events` only contains unique events.

### Why not a separate dedup table

A dedicated `(partner, eventId)` table would be smaller and faster to check. But it would be a second source of truth for "have we seen this?" and would lose the record of what was submitted. The current design keeps `raw_events` as the single source of truth for everything received, which matters more for audit than a marginally smaller table.

### Why the DB constraint still exists

The application-layer check and the DB constraint are two different safety nets:
- **Application check** handles the API contract — structured response, logging
- **DB constraint** is the hard backstop — enforces uniqueness even if the application check has a bug

`derived_events` has no uniqueness constraint — it relies on `raw_events` as the deduplication gate and only accepts events that passed through it.

---

## 6. No Grace Window

### Decision

There is no waiting period for out-of-order events. An event with an older `receivedAt` than the current state is stored and audited but does not update state. No hold, no delay, no speculation.

### Why not a grace window

A grace window sounds straightforward but has problems:
- It adds latency to state updates — callers waiting for a possibly-held event
- The correct duration depends on observed out-of-order frequency — 5 minutes without data could be too strict or too lenient
- It doesn't fix skew — it just delays processing; the underlying timestamp is still wrong
- It complicates the processing model — held events need to be managed, released, potentially re-evaluated

The current design is deterministic and immediate. The cost is that an event that arrives physically late and is genuinely older by `receivedAt` is correctly rejected, while an event that arrives physically late but is newer by `receivedAt` is correctly accepted. The trade-off is acceptable.

---

## 7. Why Not Kafka

### Decision

Kafka was deliberately excluded from Phase 1.

### Why not

**The problem doesn't require it.**
The system receives webhook calls and processes them synchronously. There is one authoritative service consuming one webhook. Kafka's strength — multiple independent consumers processing the same stream differently — is solving a problem we don't have.

**Scope control.**
Kafka adds significant infrastructure: cluster management, topic configuration, consumer groups, offset management, dead letter queues, consumer lag monitoring, partitioning strategy. All of that would distract from the core business logic.

**Lightweight intent.**
SQLite was chosen deliberately — zero configuration, easy to run, appropriate for a demonstration. Kafka requires a completely different operational model.

### When Kafka makes sense

- Multiple independent consumers processing events in different ways
- Genuine async processing with replay required
- Volume demands partitioning across consumers
- Event fan-out to downstream systems

None of these were Phase 1 concerns.

---

## 8. Why Not Full Event Sourcing

### Decision

The architecture borrows event sourcing concepts (append-only event store, replay capability) but stops short of full event sourcing.

### Why not

Full event sourcing requires rebuilding shipment state from the entire event stream on every query, or maintaining projections. Our dominant read use case is retrieving the current shipment status — a single-row lookup.

By maintaining append-only event history alongside derived current state:
- O(1) status lookups — query `shipment_current_state` directly
- Full audit history — `raw_events` and `derived_events`
- Replay capability — `raw_events` can be reprocessed if logic changes

Trade-off: we don't have the full historical fidelity of pure event sourcing, but we avoid the query-time recomputation cost.

---

## 9. SQLite for Phase 1

### Decision

SQLite is the Phase 1 database. PostgreSQL migration is the documented path when scale demands it.

### Why SQLite

- Zero operational overhead — no separate service to provision
- Appropriate for the Phase 1 workload — single partner, moderate event volume
- Easy local development and testing
- Sufficient until write throughput approaches 500–1000 writes/sec or multiple partners increase volume

### Why PostgreSQL is the migration path

SQLite's primary limitation is its single-writer model. Under multiple concurrent writers or high volume, lock contention becomes a bottleneck.

The migration strategy:
1. Introduce PostgreSQL alongside SQLite
2. Enable dual-write through feature flags
3. Validate consistency through reconciliation
4. Migrate historical data
5. Cut over reads and writes
6. Remove SQLite

Domain model, resolver logic, and event processing pipeline remain unchanged — the repository abstraction isolates business logic from persistence.

### What would change

- Connection pool configuration
- SQL dialect differences
- Database infrastructure (managed service vs local disk)

What wouldn't change:
- The three-store model
- The deduplication strategy
- The resolver interface
- Event processing pipeline

---

## 10. Orphaned Derived Events

### What they are

An orphaned derived event is a derived event in `derived_events` with no corresponding raw event in `raw_events`. The inverse — a raw event with no derived event — is also possible but less likely.

### How they happen

Normal flow:
1. Raw event arrives
2. Deduplication check
3. Raw event persisted to `raw_events`
4. State resolution — resolver computes new state
5. Derived event and current state update — same transaction

If cleanup deletes the raw event at step 3 (before step 5 completes), the derived event is never created — but if cleanup runs after both writes and deletes raw while derived survives, you get an orphan.

The more likely scenario: raw events are deleted by cleanup while derived events survive (they have different retention windows). Unless cleanup deletes from both stores transactionally, the stores can drift.

### Why they're a problem

Reconciliation that replays raw events won't produce the orphaned derived event — the raw is gone. The derived event is invisible to replay but still appears in the audit trail, making it look like a transition with no origin.

### Prevention

Transactional cleanup across both stores: delete from `raw_events` and `derived_events` in the same transaction, using `shipmentId` and `receivedAt` as the join key. If one delete fails, both roll back.

---

## 11. Playback: Derived Events vs Raw Events

### What derived-event playback gives you

Derived events already contain the deduplicated, ordered, validated transitions. Replaying them in `receivedAt` order reconstructs the current state correctly — the same result as replaying raw events but starting further down the pipeline. Sufficient for state correction after a bug fix.

### What you lose

| Derived-event replay gives you | What you lose |
|------------------------------|--------------|
| Correct state recomputation | Original raw payloads (gone after 30-day cleanup) |
| Accepted transitions in order | Rejected events — out-of-order and invalid transitions are not in derived store |
| Audit log explains decisions | Full evidence chain: what the partner actually sent |
| | Ability to re-examine the raw input that triggered a rejection |

### After raw cleanup

Once raw events are deleted at 30 days, anything older can only be replayed from derived events. State can be recomputed but original inputs cannot be examined. Whether this is acceptable for legal or dispute purposes should be confirmed with the client's legal team.

---

## 12. Dispute Endpoint and Legal Hold

### The problem

Raw events are deleted at 30 days. If a dispute surfaces on day 25 and isn't resolved by day 30, the evidence is gone. The terminal-state exemption only protects closed shipments (`DELIVERED`, `RETURNED`) — active shipments have no protection.

### The solution

A dispute endpoint that activates legal hold, exempting a shipment's raw events from cleanup.

**Raise dispute:**

```
POST /api/v1/shipments/{shipmentId}/disputes
{
  "reason": "customer_reported_wrong_address",
  "raisedBy": "support-agent-42",
  "notes": "..."
}
```

Creates a dispute record, sets `legal_hold = true` on the shipment.

**Resolve dispute:**

```
PUT /api/v1/shipments/{shipmentId}/disputes/{disputeId}
{
  "status": "RESOLVED",
  "resolution": "package_located_at_customs_facility",
  "resolvedBy": "support-agent-42"
}
```

Clears the hold. Normal 30-day retention resumes on next cleanup cycle.

### Lifecycle

1. Dispute raised → `legal_hold = true` → raw events exempt from cleanup
2. Dispute investigated → raw events preserved
3. Dispute resolved → `legal_hold = false` → normal retention resumes
4. If 30-day window hasn't expired, cleanup catches them on next run

### What cleanup must check

The cleanup job must check `dispute_hold` on every delete:

```sql
DELETE FROM raw_events
WHERE receivedAt < :cutoffDate
  AND shipment_id NOT IN (
      SELECT shipment_id FROM shipment_current_state WHERE dispute_hold = true
  )
```

The same check applies to `derived_events` and `audit_log`. The orphan cleanup logic also needs the hold exclusion.

**Deployment order:** The cleanup flag check must ship at the same time as the dispute endpoint. If the endpoint exists but cleanup ignores the flag, there is a window where raising a dispute provides no protection.

### What it requires

| Component | Description |
|-----------|-------------|
| `disputes` store | Tracks dispute ID, shipment, reason, status, raisedBy, resolvedBy |
| `shipment_current_state.dispute_hold` | Boolean flag; cleanup checks this before deleting |
| Cleanup job update | Skip any shipment where `dispute_hold = true` |
| Authorisation | Only authorised agents can raise and resolve disputes |
| Audit log | Dispute lifecycle events are recorded |

### Acceptable evidence after raw cleanup

If raw events are deleted before resolution, derived events and audit log may still be usable — they show what the system decided and why. Whether this constitutes acceptable evidence should be confirmed with the client's legal team before setting the retention window.

---

## 13. The receivedAt Bug: What Happened

### What occurred

ADR-001 was updated to specify `receivedAt` as authoritative when the change request introduced Partner B and its backfill behaviour. However, the resolver code was not updated at the same time — it still partially relied on `occurredAt`.

This was not a design flaw — the ADR was correct. The gap was a process failure: an architectural document was updated without updating the code that implemented it.

The tests were written with both timestamps set to identical values. They passed regardless of which timestamp the code actually checked — they never exercised the difference between `receivedAt` and `occurredAt`.

### Why the state machine didn't catch it

The state machine validates structure, not timestamp authority. An event processed using `occurredAt` instead of `receivedAt` would still produce structurally valid transitions. The bug was invisible to the state machine.

### Why tests didn't catch it

The tests verified that older events don't update state and newer events do, but they used identical timestamp values for both fields. The code under test could use either timestamp and the tests would pass.

A test with divergent timestamps — `occurredAt` earlier than `receivedAt`, or vice versa — would have caught it immediately.

### Prevention

- Timestamp divergence test fixtures — tests must intentionally use different values for `occurredAt` and `receivedAt`
- ADR-to-code traceability — when a PR touches an ADR, the reviewer explicitly checks whether the code matches
- Architecture tests — automated tests that validate ordering behaviour independent of the resolver
- Integration tests — end-to-end sequences covering realistic out-of-order scenarios

---

## 14. Key Assumptions

The following assumptions are load-bearing. If they prove wrong, parts of the design need revisiting:

| Assumption | Risk if wrong |
|-----------|--------------|
| `receivedAt` is trustworthy and monotonically increasing per partner | Clock skew causes state regressions; see Section 3 |
| `eventId` is stable across partner retries | Unstable eventId causes duplicate processing; deduplication fails silently |
| Current-state queries dominate the access pattern | If full event replay is the dominant read, event sourcing becomes more attractive |
| Terminal states are truly terminal without correction | Wrong terminal state locks in wrong answer; correction mechanism needed |
| Legal retention windows are defined and enforceable | 30-day raw / 1-year audit must be agreed with legal; legal hold must be triggered reliably |
| Single-writer throughput is sufficient for Phase 1 | Write contention triggers PostgreSQL migration earlier than planned |
| Partner B's out-of-order rate is manageable | High out-of-order rate makes grace window necessary; current design has no hold |

---

## 15. Corrections

### The problem

`DELIVERED` and `RETURNED` are terminal by design — no incoming courier event can change them. But terminal states can be wrong. The package was delivered to the wrong address. The return was initiated fraudulently. The system correctly processed the courier's event and correctly locked the state, but the underlying reality is different.

Without a correction mechanism, the options are:
1. **Manual database update** — an operator with direct DB access changes `current_state` directly. This bypasses the audit trail and creates a state change with no record of who did it or why.
2. **No correction** — the wrong terminal state stands. Customer support sees `DELIVERED`. The system is wrong and there is no path to fix it.

Neither is acceptable in production.

### Why corrections bypass the state machine

A correction is not a courier event. It is an administrative override. It says: "set the state to X regardless of what the courier said, and regardless of what the state machine allows."

The state machine governs how shipments move based on courier events. Corrections govern how humans correct errors. These are different concerns — mixing them would require the state machine to know about administrative overrides, which couples administrative authority to event processing logic.

The correction path is a direct write to `current_state`. The state machine is never consulted.

### Why not a correction event type?

A correction event type would flow through the normal event processing path — deduplication, resolution, state machine validation. This is wrong because:
- Corrections need to exit terminal states — the state machine would reject them
- Corrections need authorization — the webhook is open to anyone
- Corrections need a different audit trail — they should record `authorisedBy`, not just `partner`

A separate admin endpoint with bearer token authentication is the right separation.

### What corrections write

| Store | Written? | Why not? |
|-------|----------|----------|
| `raw_events` | No | Corrections are not courier events. Writing them to `raw_events` mixes admin overrides with partner events. |
| `derived_events` | No | Corrections are not canonical business events. They don't represent what the courier reported. |
| `current_state` | Yes — direct write | The authoritative state changes. This is the only write needed. |
| `audit_log` | Yes — enriched | Records `eventType=CORRECTION`, `authorisedBy`, `correctionReason`. |

### Authorization

The correction endpoint requires a bearer token. The operator's permissions are checked against a permissions matrix:

```
Operator  |  DELIVERED→IN_TRANSIT  |  DELIVERED→RETURNED  |  RETURNED→IN_TRANSIT
----------|------------------------|----------------------|------------------------
agent-42  |  ✅ allowed            |  ❌ not allowed     |  ✅ allowed
admin-1   |  ✅ allowed            |  ✅ allowed          |  ✅ allowed
```

Anonymous corrections are never allowed. The `authorisedBy` field is always populated.

### Corrections vs disputes

Disputes and corrections solve different problems:

| | Dispute | Correction |
|--|---------|-----------|
| **Problem** | Evidence about to be deleted | Authoritative state is wrong |
| **Action** | Activates legal hold | Overrides current state |
| **Writes to** | `dispute_hold` flag on `current_state` | `current_state` directly |
| **Who triggers** | Support agent | Authorised administrator |
| **Target** | Active shipments | Terminal states |

They are independent mechanisms. A dispute can be raised for a shipment that is later corrected. The dispute preserves evidence; the correction changes the state.

### What changes when corrections are added

| Concern | Current | With corrections |
|---------|---------|-----------------|
| Terminal states | Final — no outgoing transitions | Corrections can exit them |
| Resolver logic | Single path | Branches on `eventType` |
| Authorization | None (open webhook) | Admin endpoint with bearer token |
| Audit trail | Standard event logging | Enriched with `authorisedBy`, `correctionReason` |
| State machine | `DELIVERED` and `RETURNED` are sinks | Unchanged for normal path; correction path bypasses |
| Dispute mechanism | Independent | Independent — no interaction |
