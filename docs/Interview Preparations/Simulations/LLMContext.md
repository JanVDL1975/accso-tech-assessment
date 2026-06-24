# LLM Interview Context — Shipment Integrity Service

You are conducting a technical interview. The candidate built a thin-slice microservice for a shipment integrity system. Below is all the context you need to ask informed follow-up questions.

---

## What Was Built

A Spring Boot 3 microservice (Java 17, SQLite) that:
- Receives shipment events from courier partners via webhook
- Deduplicates by `(partner, eventId)`
- Resolves authoritative current state using `receivedAt` as the ordering signal
- Stores raw events (30-day retention), derived events (indefinite), and audit decisions (1-year retention)
- Exposes APIs: `POST /api/v1/shipments/events`, `GET /status`, `GET /events`, `GET /audit`, `GET /health`

The processing pipeline: raw event stored → deduplicated → resolved → (if accepted) derived event stored + current state updated → audit logged.

---

## Key Architecture Decisions

| Decision | Rationale |
|----------|-----------|
| `receivedAt` for ordering | `occurredAt` is backfilled by partners; `receivedAt` is the partner's own ingestion timestamp — more reliable |
| `(partner, eventId)` deduplication key | `eventId` is not globally unique across partners |
| Append-only event store + derived current state | O(1) status queries; full history for audit; replay available if state drifts |
| Per-event batch processing | One bad event doesn't poison the batch; partial success is expected and caller handles retries |
| `ShipmentStateResolver` pluggable interface | Allows partner-specific resolution logic without modifying core pipeline |

---

## The Confirmed Bug (receivedAt)

ADR-001 specifies `receivedAt` as authoritative. The resolver code was written before Partner B was in scope and still used `occurredAt`. Tests set both timestamps identically, making the bug invisible. Fix: update resolver, add regression test with divergent timestamps.

---

## Underspecified Items the Candidate Resolved

| Item | Resolution |
|------|-----------|
| Deduplication key | `(partner, eventId)` — confirmed with client |
| Authoritative timestamp | `receivedAt` — `occurredAt` is unreliable for Partner B |
| Out-of-order handling | Store older events, don't update state |
| Batch semantics | Per-event isolation, partial success allowed |
| Terminal state corrections | No first-class mechanism — manual DB update or new event type needed |
| Retention | 30-day raw / 1-year audit — legal requirement |

---

## Where the Candidate Will Have Thought Deeply

**Architecture trade-offs:**
- Why not Kafka? (single consumer, webhook not streaming, scope control)
- Why not event sourcing? (O(n) queries, history is not the primary read)
- Why not compute-on-query? (state drift risk, latency)
- SQLite vs PostgreSQL migration trigger and path

**The bug:**
- How it slipped through tests (both timestamps set to same value)
- Process fix (ADR-to-code traceability, regression test with divergent timestamps)
- Recovery if it had shipped (append-only store allows replay)

**Operational concerns:**
- Terminal state corrections — no mechanism exists
- Partner B out-of-order rate unknown until live traffic
- SQLite single-writer bottleneck
- No metrics or alerting in Phase 1

**AI's role:**
- What it accelerated (diagrams, well-specified code patterns)
- What it couldn't do (requirements extraction, trade-off reasoning, spotting its own output mismatches)

---

## Suggested Follow-Up Questions Per Topic

**On the bug:**
- "The ADR was updated but the code wasn't. What process should have caught that?"
- "What would the regression test look like?"
- "If this had shipped and Partner B found it, how would you have detected it?"

**On architecture:**
- "You chose SQLite. What would make you migrate to PostgreSQL and how would you do it?"
- "The repository layer — would it change if you switched databases?"
- "If you needed a different resolver for Partner B, how would you plug it in?"

**On the DDD question (if asked):**
- "Would the architecture change if you reorganised by domain concept instead of technical layer?"
- They should explain: aggregate root, repository interfaces in domain, domain events vs audit log, what stays same on Spring Boot

**On operational gaps:**
- "What happens when a shipment needs a correction after it's delivered?"
- "What would you instrument first if this went to production?"
- "Retention cleanup runs at 2 AM. What happens if it takes too long?"

**On AI:**
- "Give me a specific example where AI's output was wrong and you caught it."
- "What did you decide not to build because AI suggested it?"

---

## The Code Structure

```
/controller    — HTTP endpoints (thin, delegates to service)
/service      — ShipmentEventService (orchestration + business logic)
              — RetentionCleanupService (scheduled jobs)
/domain       — ShipmentStatus enum + transition rules
/entity       — JPA entities (raw_events, derived_events, current_state, audit_log)
/dto          — API request/response objects
/repository   — Spring Data JPA interfaces
/resolver     — ShipmentStateResolver interface + DefaultShipmentStateResolver
```

---

## What to Listen For

- **Specificity** — do they cite concrete numbers (500–1000 writes/s), exact ADR names (ADR-001), or just vague claims?
- **Ownership of the bug** — do they say "the tests were wrong" or "I didn't catch that the code didn't match the ADR"?
- **Trade-off framing** — do they present alternatives or just defend what they built?
- **Process thinking** — when asked "what would stop this happening again?" do they say "better tests" or do they identify the ADR-to-code gap as a process problem?
- **Honesty about gaps** — do they admit Phase 1 has no metrics, no alerting, no correction mechanism?

---

## What Not to Ask About

- How to implement a grace window (not implemented, deferred to Phase 2 — they should say so)
- Whether Kafka would be better (they have a prepared defence)
- Details of the correction event type design (they may have thought about it but it's not implemented)
