# Failure Modes — receivedAt Clock Skew

## The Assumption

`receivedAt` is treated as the authoritative timestamp for ordering events. The system assumes this value is trustworthy and monotonically increasing per partner. This assumption holds under normal conditions but breaks under clock skew.

---

## What Is Clock Skew?

Clock skew occurs when a partner's server clock drifts from true time — either ahead (running fast) or behind (running slow). Since `receivedAt` is set by the partner's system, not ours, we inherit that drift.

Skew is bounded in normal conditions but can be significant in edge cases:
- Partner B's batch model means events in the same batch share a common skew window
- NTP misconfiguration, virtual machine clock drift, or scheduled batch jobs running off-hours can all cause skew

---

## Failure Mode: Future-Dated Events

### What happens

An event arrives with a `receivedAt` that is in the future relative to our clock or to other events from the same partner. The system compares timestamps lexicographically — it has no mechanism to detect that a timestamp is implausible.

### Walk-through

1. Current state: `lastReceivedAt = 12:00`
2. Event A arrives — legitimate, `receivedAt = 12:05` — state updated correctly
3. Current state: `lastReceivedAt = 12:05`
4. Event B arrives — physical arrival order is correct, but Partner B's clock was running fast, so `receivedAt = 12:08` (skewed future)
5. System checks: `12:08 > 12:05`? Yes — state is updated
6. But Event B's `occurredAt = 11:55` — it was genuinely older

### Result

A genuinely older event overwrote correct state. The shipment state regressed or took an incorrect path.

### Why the state machine does not prevent it

The state machine validates that a transition is **legally valid** (e.g., `IN_TRANSIT → OUT_FOR_DELIVERY`), not that it is **temporally correct**. As long as the transition is structurally valid, it passes through.

- `IN_TRANSIT → OUT_FOR_DELIVERY` is valid — accepted even if the event was actually older
- `DELIVERED → IN_TRANSIT` would be rejected — state machine catches this

Partial protection, not complete. The state machine is a sieve, not a shield.

---

## Failure Mode: Back-Dated Events

### What happens

An event arrives with a `receivedAt` that is significantly in the past — the partner's clock was running slow. The system correctly rejects it as older (since `receivedAt < lastReceivedAt`), but the rejection is correct by accident, not by design.

### Result

Valid, genuinely newer events from the same partner could be incorrectly rejected if their timestamps also carry the same slow skew and fall below the current state's threshold.

---

## Why This Is Dangerous

**The system continues functioning and returning answers.** No exception is thrown, no error is logged as such. The answers are simply wrong in ways that are hard to detect without active reconciliation.

The wrongness is silent because:
- The state machine accepts the transition (it is structurally valid)
- The ordering comparison passes (`receivedAt` is higher)
- The event is processed and committed normally
- The audit log records a decision that looks correct

Detection requires a reconciliation job that rebuilds state from raw events using an independent ordering signal and compares the result against the stored derived state.

---

## Mitigations

| Mitigation | How it helps | Limitation |
|------------|--------------|------------|
| Bounded skew detection | Flag events with `receivedAt` far from our wall clock | Only catches extreme skew; needs a threshold |
| Reconciliation job | Detects state drift after the fact | Reactive, not preventive |
| Grace window | Holds borderline events briefly | Does not fix skew; delays processing; threshold is arbitrary |
| `occurredAt` cross-check | Flag large gaps between `receivedAt` and `occurredAt` | Only indicates skew, doesn't resolve it |

None of these prevent the wrong state from being written — they only increase the chance of detecting it.

---

## Interaction with Other Failure Modes

**Duplicate events + skew:** If the same physical event is retried with a different (more skewed) `receivedAt`, it could pass the deduplication check but fail or succeed the ordering check inconsistently.

**Batch ingestion + skew:** Partner B's batch model means all events in a batch share the same skew window. If the batch's `receivedAt` is skewed, every event in that batch is affected simultaneously — a single skew event could corrupt multiple shipments.

**Terminal states + skew:** A skewed event reaching a terminal state (`DELIVERED`, `RETURNED`) cannot be corrected without a first-class correction mechanism. The wrong terminal state sticks.

---

## Open Question

What is the acceptable skew threshold before Partner B's `receivedAt` values should be flagged? This needs to be agreed with the client and calibrated against observed Partner B clock behaviour before going to production.

---

# Full Architecture Failure Modes

---

## Ingestion

---

### Partner Retry Storm

**Problem:** A partner retries aggressively during a network hiccup. Events land rapidly, each passing deduplication checks. There is no rate limiting or back-pressure on the webhook — the system accepts everything.

**Solution:**

Introduce per-partner rate limiting at the ingestion layer. Reject events that exceed a defined events-per-second threshold with `429 Too Many Requests`. This forces the partner to honour back-off rather than hammering the endpoint.

Implement idempotent retry handling on the partner side (the partner stores our response to a duplicate submission before retrying), but this requires partner cooperation. Without that, rate limiting protects the service from being overwhelmed.

---

### Malformed Payloads Bypass Validation

**Problem:** Partner-specific payload validation is deferred. A buggy partner could send a structurally valid but semantically wrong payload — wrong enum value, impossible timestamp, missing required field — that we store and process.

**Solution:**

Add partner-specific JSON Schema validation at ingestion. Each partner gets a schema that describes their expected payload shape. Payloads that fail validation are rejected immediately with a structured error, not stored.

This can be implemented incrementally: start with a generic "any valid JSON" guard, then add per-partner schemas as each partner is onboarded.

---

### Timeout Before Commit

**Problem:** The partner sends the event, we process it, but the database commit happens after our HTTP response is sent. If the connection drops before commit, the event is lost. The partner retries, but deduplication only works if `raw_events` was written first.

**Solution:**

The window between event write and state update is vulnerable. For each incoming event, the entire unit of work — deduplication check, raw event write, derived event write, current state update — should be a single atomic transaction. If the HTTP response cannot be sent (connection drop, timeout), the transaction rolls back and the event is not committed.

On the partner's side, they retry with the same `eventId`. Deduplication catches the retry on the second attempt and returns `200 OK` with `reason=DUPLICATE_EVENT`. The partner's retry is idempotent by design.

The risk remains if the commit succeeds but the response is lost in flight — the partner retries unnecessarily, but deduplication handles it. The fix is ensuring the transaction is committed before the response is sent.

---

## Deduplication

---

### Unstable EventId Across Retries

**Problem:** We assume `eventId` is stable across partner retries. If Partner B regenerates a new UUID on each retry attempt, each retry is treated as a new event and processed as such. State could accumulate duplicate valid transitions instead of collapsing to one.

**Solution:**

Introduce a payload hash (`SHA-256(eventId + shipmentId + eventType + timestamp)`) as a secondary deduplication signal. If the partner sends the same physical event with a different `eventId` but the same payload hash, flag it for review rather than processing as a new event.

Alternatively, agree a stable eventId contract with each partner — they commit to using the same `eventId` for retries. This is an operational agreement, not a technical fix.

---

### Payload Mutation with Same EventId

**Problem:** A partner retries with the same `eventId` but a corrected payload (different status). The current system silently rejects the second attempt. The partner never knows their correction didn't register and the original wrong state persists.

**Solution:**

Detect payload mutation on duplicate events. Compare the incoming payload hash against the stored payload hash for the same `(partner, eventId)`. If they differ, instead of silently rejecting:

- Log a `PAYLOAD_MUTATION` event with both payloads and the discrepancy
- Alert operations
- Expose a admin API endpoint to inspect and manually resolve the conflict

This preserves the audit trail and prevents silent data loss, while allowing human review for genuinely corrected payloads.

---

## State Resolution

---

### Race Condition on Concurrent Events

**Problem:** Two events for the same shipment arrive simultaneously on different threads. Thread A reads current state, Thread B reads current state, both compute independently, both write. The last write wins — but which one wins is determined by thread scheduling, not by which event is genuinely newer.

**Solution:**

Implement optimistic locking on the `shipment_current_state` table using a version column. On update:

```
UPDATE shipment_current_state
SET status = :newStatus, version = version + 1
WHERE shipment_id = :id AND version = :expectedVersion
```

If the rows affected is zero, the version mismatch means another thread updated the state. Retry the read-compute-write cycle.

This makes the outcome deterministic: the genuinely newer event (by `receivedAt`) wins, regardless of which thread scheduled first. The retry cost is small under low contention and the lock is held only for the duration of the update, not the entire read-compute-write cycle.

---

### Partial Write Failure

**Problem:** The resolver computes a new state and attempts to write both a derived event and update `current_state` in the same transaction. If `current_state` write succeeds but the derived event write fails (constraint violation, connection loss mid-commit), the state is updated but the audit trail is missing the transition. Reconciliation would detect this but cannot reverse it.

**Solution:**

This is an atomic transaction problem. Both writes must succeed or both must roll back. The fix is a single transaction covering both:

```java
@Transactional
public void processEvent(Event event) {
    // all reads and writes in one transaction
    DerivedEvent derived = resolver.resolve(event, currentState);
    derivedEventRepository.save(derived);
    currentStateRepository.save(updatedState);
    // commit or rollback together
}
```

If the transaction fails at any point, both writes roll back. The partner retries with the same `eventId`, deduplication catches it, and the event is reprocessed cleanly.

---

## Retention Cleanup

---

### Cleanup Runs on Live Data

**Problem:** The cleanup job deletes records based on `receivedAt`. If it takes row locks during peak traffic, event ingestion latencies spike.

**Solution:**

Run cleanup jobs during a defined low-traffic window (e.g., 2–4 AM local time). Add a lock timeout so the job yields if it can't acquire a row lock within a few seconds, preventing it from blocking live ingestion.

Alternatively, use batched deletes with a limit per run (`DELETE FROM ... LIMIT 1000`) and a delay between batches, so the job makes progress without ever holding locks for long.

---

### Raw Evidence Deleted Mid-Dispute

**Problem:** A shipment is `IN_TRANSIT` and its raw events are deleted by the cleanup job. Three days later the customer disputes and the raw evidence needed for investigation is gone.

**Root cause:** The terminal-state exemption only protects closed shipments (`DELIVERED`, `RETURNED`). Active shipments have no protection — cleanup proceeds regardless of whether a dispute is in progress.

**The core tension:** If retention is 30 days from event receipt, and a dispute surfaces on day 25, the raw events are deleted on day 30 before the dispute is resolved. If retention is measured from dispute open, disputes that take months still lose evidence.

**Solution — Dispute Endpoint + Legal Hold:**

The solution mirrors the correction event pattern. A dispute is an explicit trigger that activates legal hold, exempting the shipment's raw events from cleanup.

**Raise dispute:**

```
POST /api/v1/shipments/{shipmentId}/disputes
{
  "reason": "customer_reported_wrong_address",
  "raisedBy": "support-agent-42",
  "notes": "..."
}
```

This creates a dispute record, sets `legal_hold = true` on the shipment, and exempts all raw events from cleanup for that shipment.

**Resolve dispute:**

```
PUT /api/v1/shipments/{shipmentId}/disputes/{disputeId}
{
  "status": "RESOLVED",
  "resolution": "package_located_at_customs_facility",
  "resolvedBy": "support-agent-42"
}
```

Setting `status = RESOLVED` clears the hold. Normal 30-day retention resumes on the next cleanup cycle.

**Lifecycle:**

1. Dispute raised → `legal_hold = true` → raw events exempt from cleanup
2. Dispute investigated → raw events preserved throughout
3. Dispute resolved → `legal_hold = false` → normal retention resumes
4. If 30-day window hasn't expired yet, cleanup catches them on next run

**What it requires:**

| Component | Description |
|-----------|-------------|
| `disputes` store | Tracks dispute ID, shipment, reason, status, raisedBy, resolvedBy |
| `shipment_current_state.dispute_hold` | Boolean flag; cleanup job checks this before deleting |
| Cleanup job update | Skip any shipment where `dispute_hold = true` |
| Authorisation | Only authorised agents can raise and resolve disputes |
| Audit log | Dispute lifecycle events are recorded |

**Cleanup job changes:**

The cleanup job must check `dispute_hold` on every delete. What was:

```sql
DELETE FROM raw_events WHERE receivedAt < :cutoffDate
```

Becomes:

```sql
DELETE FROM raw_events
WHERE receivedAt < :cutoffDate
  AND shipment_id NOT IN (
      SELECT shipment_id FROM shipment_current_state WHERE dispute_hold = true
  )
```

The same check applies to `derived_events` and `audit_log` — all three stores must skip held shipments. The orphan cleanup logic also needs the hold exclusion: when deleting from both stores transactionally, the `NOT IN (held shipments)` clause applies to both sides.

**Deployment order:** The cleanup flag check must ship at the same time as the dispute endpoint, not after. If the endpoint exists but cleanup ignores the flag, there is a window where raising a dispute provides no protection.

**Trigger reliability:**

The dispute endpoint is only effective if the trigger is reliable. The gap is when a dispute surfaces through a channel we don't control — a legal notice, a chargeback, a direct customer complaint to the courier. In those cases we may never call the endpoint and the evidence degrades silently.

Mitigation: define SLAs with partners and customer support requiring disputes to be registered via the API within a defined window of the incident. The shorter the window relative to the retention period, the less risk of evidence loss.

**Acceptable evidence:**

If raw events are deleted before a dispute is resolved, derived events and audit log may still be usable — they show what the system decided and why. However, playback from derived events is limited:

| What derived-event replay gives you | What you lose |
|-----------------------------------|--------------|
| Correct state recomputation | Original raw payloads (gone after 30-day cleanup) |
| Accepted transitions in order | Rejected events (out-of-order, invalid transitions — not in derived store) |
| Audit log explains decisions | Full evidence chain: what the partner actually sent |
| | Ability to re-examine the raw input that triggered a rejection |

Derived-event playback is sufficient for state correction after a bug fix. It is insufficient for dispute investigation, partner bug detection, or legal evidence, where the original raw payload is needed.

**Playback after raw cleanup:** Once raw events are deleted, anything older than 30 days can only be replayed from derived events. State can be recomputed but original inputs cannot be examined. Whether this is acceptable for the client's legal team should be confirmed before setting the retention window.

---

### Orphaned Derived Events

**Problem:** A raw event is deleted by cleanup but its corresponding derived event is not, or vice versa. The stores drift. Reconciliation would catch this but there's no automated repair.

**Solution:**

Cleanup should be transactional across both stores: delete from `raw_events` and `derived_events` in the same transaction, using `shipmentId` and `receivedAt` as the join key. If one delete fails, both roll back.

A reconciliation job that detects mismatches between the two stores should also include an automated repair option: if a raw event is missing but a derived event exists, flag for human review rather than auto-deleting the derived event. Orphaned derived events (no corresponding raw event) should be deleted as part of the repair.

---

## Terminal States

---

### Wrong Terminal State, No Recovery

**Problem:** A `DELIVERED` event was accepted but was wrong — the package was actually lost. Terminal state locks are correct by design but they lock in the wrong answer. Without a first-class correction mechanism, the system returns a final wrong state indefinitely.

**Solution:**

Introduce a dedicated **Correction Event** type. A correction is an administrative action, not a courier event — it bypasses the state machine but is appended to the event stream rather than modifying existing records.

The correction event contains:
- Original state
- Corrected state
- Reason
- Requestor
- Approver
- Timestamp

The derived state is recomputed treating both operational events and corrections as valid inputs. Corrections are queryable and auditable, and the correction chain is preserved rather than overwriting history.

---

## Concurrency

---

### Multi-Instance Writes to SQLite

**Problem:** SQLite serialises writes but concurrent reads are fine. If the deployment runs multiple service instances sharing a SQLite file (not the intended deployment, but possible in containerised environments without a shared volume), file-level locking means writes fail or queue unpredictably.

**Solution:**

The intended deployment targets a single instance with SQLite on local disk. To prevent accidental multi-instance misuse, add a startup check that verifies only one instance can acquire the database file lock.

The proper long-term fix is PostgreSQL migration, which handles multi-instance writes correctly.

---

### Read-Then-Write Race

**Problem:** The resolver pattern reads current state, computes new state, then writes. Under high concurrency this is a classic race condition. Even without concurrent threads, two events processed in quick succession could both read the same state before either writes back.

**Solution:**

Optimistic locking (described above under race conditions) handles this by detecting when the state changed between read and write. If the version has incremented, the write fails and the operation retries with the latest state.

The alternative — pessimistic locking with `SELECT FOR UPDATE` — holds a lock for the duration of the entire read-compute-write cycle, which hurts throughput under contention. Optimistic locking is preferred.

---

## API / Query Path

---

### Stale Reads

**Problem:** The `current_state` table is updated after the derived event. A query that arrives between the two writes sees the old state. This is eventual consistency by design but could surprise synchronous callers expecting immediate consistency.

**Solution:**

For a single-node deployment this window is microseconds and the risk is negligible. Document the eventual consistency guarantee explicitly in the API contract so callers understand the behaviour.

If stronger guarantees are needed for specific endpoints, add a read-your-writes check: after a successful event ingestion, queries for that `shipmentId` can be routed to the writer node directly (bypassing read replicas) for a short window, or the response can include the new `lastReceivedAt` so the caller can confirm they have the latest.

---

### No API Versioning

**Problem:** The API has no version prefix. Changes to response shape break clients without warning.

**Solution:**

Introduce `/api/v1/` as the version prefix for all endpoints from the start. Any breaking changes go to `/api/v2/`. Non-breaking additions (new fields in responses) can ship on v1 without a version bump.

This is a one-time decision that is cheap to implement early and expensive to retrofit later.

---

## Storage

---

### Disk Full

**Problem:** SQLite on local disk. If the disk fills, writes fail. There is no mechanism to alert on available disk space, stage events to a queue, or shed load gracefully.

**Solution:**

Add disk space monitoring: an alerting rule that fires when available disk space drops below a threshold (e.g., 20% of capacity). On the write path, check available space before each transaction and reject with a clear error if space is critically low.

For a more resilient design, introduce an event staging table: if the primary write fails due to disk pressure, events are written to a staging area and a background job retries them once space is available. This requires careful ordering to prevent the staging area itself from filling up.

---

### Audit Log Fills First

**Problem:** `audit_log` grows indefinitely for active shipments. A high-volume partner could cause the audit table to consume disproportionate storage relative to events.

**Solution:**

Apply the same retention policy to `audit_log` as to the other stores — entries older than 1 year are deleted by the cleanup job. Unlike raw events (30-day) and audit decisions (1-year), there is no reason to retain audit log entries beyond the defined window.

Monitor `audit_log` table size as part of the storage metrics. If growth rate exceeds expectations, investigate whether the resolver is writing an unexpectedly high volume of audit entries per event.

---

## Partner Behaviour

---

### Partner B Batch Overwhelms Processing

**Problem:** A large batch arrives and is processed event-by-event synchronously in a loop. Each event triggers a DB write. The batch holds the HTTP connection open. The partner could timeout, retry, and send the batch again. Deduplication catches the physical retries but processing latency is unbounded.

**Solution:**

Decouple ingestion from processing. On receiving a batch:

1. Immediately acknowledge receipt (`202 Accepted`) and store the batch in a staging area
2. Return the batch ID to the partner
3. Process events asynchronously from the staging area
4. The partner can poll a status endpoint or receive a callback when the batch is fully processed

This prevents timeout retries from causing duplicate processing and bounds the HTTP connection time. The trade-off is increased complexity: you now need a batch status API, a background processing queue, and retry logic within the processor.

---

### Partner Normaliser Not Implemented

**Problem:** The `Normaliser` interface exists for partner-specific event transformation, but no concrete implementations exist. Partner B events go through a generic resolver. If Partner B sends semantically unusual events, the generic resolver may not handle them correctly and the issue surfaces only in production.

**Solution:**

Before Partner B goes live, build and test a concrete `PartnerBNormaliser` that handles their specific event shape and semantics. The normaliser is part of the Partner B onboarding checklist, not a Phase 1 concern.

Add a contract test suite that validates each partner's normaliser against a library of their historical event samples. This regression suite prevents a future change from breaking Partner B's normaliser.

---

## Authentication

---

### Webhook Publicly Exposed

**Problem:** Without HMAC signatures, mutual TLS, or API keys, the ingestion endpoint accepts events from anyone. A malicious actor could submit fabricated events to corrupt state or exhaust storage.

**Solution:**

Implement **HMAC signature verification**. Each partner is issued a shared secret. They sign the request body with HMAC-SHA256 and include the signature in a header (e.g., `X-Partner-Signature`). On receipt:

1. Look up the partner's secret by `partner` field
2. Compute HMAC of the raw request body
3. Compare against the provided signature (constant-time comparison to prevent timing attacks)
4. Reject with `401 Unauthorized` if the signature does not match

API keys are a simpler alternative but HMAC is preferred because it verifies the payload integrity, not just the caller's identity — a compromised API key cannot be used to tamper with an individual event's content.

---

## Observability Gaps

---

### No Signal on Silent Failures

**Problem:** If events are incorrectly rejected due to a bug in the resolver, the caller gets a valid-looking response (`200 OK`) and the rejection is only visible in text logs. Without metrics on rejection reasons, operations has no observable signal.

**Solution:**

Instrument the resolver to emit structured metrics on every decision:

- Counter: `shipment.resolution.outcome` with labels `{partner, status, rejection_reason}`
- Counter: `shipment.resolution.latency_ms`

This gives a dashboard showing rejection rates per partner per reason. An unexpectedly high rejection rate for a partner triggers an alert before customers notice.

---

### Reconciliation Job Doesn't Exist

**Problem:** The failure modes document describes what reconciliation would catch, but there is no scheduled reconciliation job. State drift is undetectable until an external party notices.

**Solution:**

Implement a scheduled reconciliation job that:

1. Periodically (e.g., every hour) selects a sample of active shipments
2. Replays their raw events in `receivedAt` order
3. Computes the expected derived state
4. Compares against the stored `current_state`
5. If any mismatch is found, alerts operations and optionally auto-repairs the state

The sample size should be large enough to catch systemic drift but not so large as to add meaningful database load. A full reconciliation (all shipments, less frequently) can run during off-peak hours.

Reconciliation should also verify that `raw_events` and `derived_events` are in sync — the same set of events should produce the same derived events, and neither store should have orphans.

---

## Summary Table

| Failure | Solution |
|---------|----------|
| Partner retry storm | Per-partner rate limiting; idempotent retry contract with partners |
| Malformed payloads | Per-partner JSON Schema validation at ingestion |
| Timeout before commit | Atomic transaction; rollback on failed commit; partner retries |
| Unstable eventId | Payload hash as secondary deduplication signal; partner SLA on stable eventId |
| Payload mutation same eventId | Detect and flag payload changes; admin review API |
| Race condition concurrent events | Optimistic locking with version column |
| Partial write failure | Single atomic transaction for all writes |
| Cleanup on live data | Off-peak scheduling; batched deletes with lock timeout |
| Evidence deleted mid-dispute | Legal hold flag per shipment; exempt from cleanup |
| Orphaned derived events | Transactional cleanup across both stores; reconciliation repair |
| Wrong terminal state | Correction event type appended to stream; admin approval workflow |
| Multi-instance SQLite | Single-instance enforcement; PostgreSQL migration |
| Read-then-write race | Optimistic locking with retry on version mismatch |
| Stale reads | Document eventual consistency; read-your-writes for critical paths |
| No API versioning | Adopt `/api/v1/` prefix from the start |
| Disk full | Disk space monitoring; event staging table with retry |
| Audit log fills | Apply 1-year retention to audit_log; monitor table size |
| Batch overwhelms processing | Async batch ingestion with staging; partner callback/polling |
| Partner normaliser missing | Partner-specific normaliser as part of onboarding checklist; contract tests |
| Webhook publicly exposed | HMAC signature verification per partner |
| No silent failure signal | Structured resolver metrics: outcome counter + latency histogram |
| Reconciliation job missing | Scheduled reconciliation: replay, compare, alert, auto-repair |
