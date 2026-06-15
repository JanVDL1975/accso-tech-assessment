# Delivery Plan

**Date:** 2026-06-15
**Status:** Draft
**Related Document:** TECHNICAL_STRATEGY_MEMO.md

---

## Phase 1 - Foundation

**Objective:** Demonstrate the core approach with a working, testable slice.

### Scope

- Single-partner event ingestion endpoint (HTTPS POST)
- Canonical event model and normalisation
- Deduplication logic (per-partner `eventId`)
- Out-of-order event handling with `occurredAt` ordering and `receivedAt` tiebreaker
- Conflict resolution rules engine (deterministic, stateless)
- Append-only event store with audit trail
- Current state derivation per shipment
- Queryable current state by `shipmentId`

### Minimum Credible First Slice

An ingestion endpoint with normalisation, deduplication, state derivation, and tests. No CI/CD infrastructure, no metrics dashboard, no containerisation beyond what directly supports the slice.

### Success Signals

- Duplicate events are detected and logged without altering state
- Out-of-order events are handled deterministically with decisions recorded in the audit trail
- Conflicting events are resolved by the rules engine; terminal states (`DELIVERED`, `RETURNED`) are enforced
- Current state is queryable by `shipmentId`
- Audit trail explains every state derivation decision

### Duration

Approximately 1–2 weeks for a solo developer, assuming no blocking dependencies.

---

## Phase 2 - TBD (Post-Change Request)

To be planned via formal change request. Expected areas:

- Metrics, alerting, and observability
- Hosting model finalisation and production deployment

---

## Dependencies and Blockers

| Dependency | Owner | Notes |
|------------|-------|-------|
| Partner API contract | Courier partner | Needed for normalisation layer |
| Tech stack decisions | Engineering | Must be justified and documented |
| Open questions resolved | Client | Shipment ID lifecycle, grace window, courier-specific rules |