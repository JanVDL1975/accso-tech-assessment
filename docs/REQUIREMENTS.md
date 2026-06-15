# Requirements Specification

## Overview

This document captures the functional and non-functional requirements for the Shipping Integrity system - a solution to ensure reliable, authoritative shipment state for an e-commerce platform that ships orders through courier partners.

---

## Functional Requirements

### FR-1: Event Ingestion

The system **MUST** receive shipment events from a courier partner via webhook.

**Acceptance criteria:**
- An ingestion endpoint accepts POST requests with a JSON payload
- Incoming events are normalised into a canonical model

---

### FR-2: Event Identification and Deduplication

The system **MUST** detect and collapse duplicate events based on `eventId`. Since `eventId` is partner-scoped (not globally unique), deduplication is per-partner.

**Acceptance criteria:**
- A second ingestion of the same `eventId` for the same `partner` is recognised as a duplicate
- Duplicate events do not alter shipment state or audit trail
- Deduplication is idempotent and deterministic

---

### FR-3: Out-of-Order Event Handling

The system **MUST** handle events that arrive out of chronological order using the `occurredAt` timestamp. Note: `occurredAt` is partner-supplied and cannot be trusted as a global clock - expect clock skew between couriers, varying precision, and backfilled timestamps.

**Acceptance criteria:**
- Events are ordered by `occurredAt` when deriving state
- The system does not assume `occurredAt` reflects true wall-clock time
- A secondary ordering signal (e.g. `receivedAt`) may be used as a tiebreaker when `occurredAt` is ambiguous

---

### FR-4: Conflict Resolution

The system **MUST** apply a deterministic rules engine when events conflict. When the same shipment receives multiple competing status updates, the rules engine derives the authoritative current state.

**Acceptance criteria:**
- Rules are deterministic - same event sequence always produces the same outcome
- Conflict resolution is logged in the audit trail with the reasoning
- Terminal states (`DELIVERED`, `RETURNED`) are not overridden by earlier non-terminal states

---

### FR-5: Status Value Governance

The system **MUST** enforce the canonical status value taxonomy.

Normal path:
```
LABEL_CREATED → HANDED_TO_CARRIER → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
```

Exception path:
```
… → DELIVERY_EXCEPTION → RETURNED
```

**Acceptance criteria:**
- Only valid status transitions are accepted
- Invalid transitions are rejected and logged
- Exception states can interrupt the normal path at any point

---

### FR-6: Current State Derivation

The system **MUST** maintain a reliable, queryable current state per shipment.

**Acceptance criteria:**
- Current state is derived from the full event history
- State is queryable by `shipmentId`
- State reflects the resolution of all deduplication, ordering, and conflict decisions

---

### FR-7: Audit Trail

The system **MUST** store the full event history and the decisions made to derive state.

**Acceptance criteria:**
- Every ingested event is stored with its raw payload
- Every state derivation decision is recorded with its rationale

---


## Non-Functional Requirements

### NFR-1: Event Identifier Handling

`eventId` and `shipmentId` are opaque external IDs owned by the respective partner. Formats vary between couriers.

**Implication:** The system must treat IDs as opaque strings and must not assume UUID format, sequential ordering, or cross-partner uniqueness.

---

### NFR-2: Timestamp Reliability

`occurredAt` is partner-supplied and unreliable as a global clock.

**Implication:**
- Design must not depend on `occurredAt` being monotonically increasing
- Clock skew, varying precision, and backfilled timestamps must be handled gracefully
- `receivedAt` (partner-supplied ingestion time into their system) may be used as a secondary ordering signal

---

### NFR-3: Trust Boundary

The partner is the integration and trust boundary. Events arrive pre-aggregated via webhook, not directly from driver handhelds.

**Implication:** No requirement to validate events against driver-level data or courier internal systems.

---

### NFR-4: Hosting and Stack

Hosting model and tech stack are not prescribed.

**Implication:** Architecture decisions around serverless vs containerised deployment, language, and data store are yours to make and justify.

---

### NFR-5: Volume and Throughput

A sensible order-of-magnitude assumption for event frequency is sufficient.

**Implication:** No detailed volume modelling required at this stage. Architecture should be capable of handling reasonable e-commerce courier volumes.

---

## Identified Assumptions

The following assumptions are made where requirements are underspecified:

1. Events arrive via HTTPS POST webhook - no push-based streaming protocol required
2. Partner payloads are well-formed JSON - malformed payloads are rejected at the ingestion boundary
3. Event processing is not latency-critical beyond what is needed for customer support and tracking queries
4. No requirement to expose a public API to end customers - internal systems only
5. Deduplication window is the lifetime of the shipment record (no separate deduplication store required)
6. Conflict resolution rules are configurable but shipped with sensible defaults
7. The thin executable slice demonstrates the approach rather than providing a production deployment

---

## Out of Scope

- Complete platform build
- Full CI/CD infrastructure
- End-customer facing tracking portal
- Real-time streaming or event sourcing infrastructure beyond what the thin slice demonstrates
- Multi-region or multi-cloud deployment
- Detailed volume modelling or capacity planning