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
- Out-of-order event handling with `receivedAt` ordering
- Conflict resolution rules engine (deterministic, stateless)
- Append-only event store with audit trail
- Current state derivation per shipment
- Queryable current state by `shipmentId`
- Batch ingestion (bare array of events, auto-detected)
- Raw event store with 30-day retention (legal requirement)
- Audit decision log with 1-year retention (legal requirement)
- Scheduled retention cleanup jobs

### Minimum Credible First Slice

An ingestion endpoint with normalisation, deduplication, batch processing, state derivation, retention cleanup, and tests. No CI/CD infrastructure, no metrics dashboard, no containerisation beyond what directly supports the slice.

### Success Signals

- Duplicate events are detected and logged without altering state
- Out-of-order events are handled deterministically with decisions recorded in the audit trail
- Conflicting events are resolved by the rules engine; terminal states (`DELIVERED`, `RETURNED`) are enforced
- Current state is queryable by `shipmentId`
- Audit trail explains every state derivation decision
- Batch ingestion processes each event independently; one bad event does not poison the batch
- Raw events are deleted after 30 days; audit log entries are deleted after 1 year
- Terminal-state shipments are exempt from retention cleanup

### Duration

Approximately 2–3 weeks for a solo developer, assuming no blocking dependencies. The addition of batch processing and retention cleanup increases scope from the original estimate.

---

## Phase 2 - TBD (Post-Change Request)

To be planned via formal change request. Expected areas:

- Metrics, alerting, and observability
- Hosting model finalisation and production deployment
- Multi-partner normalisation layer (partner-specific payload mapping)
- Grace window for out-of-order events

---

## Dependencies and Blockers

| Dependency | Owner | Notes |
|------------|-------|-------|
| Partner API contract | Courier partner | Needed for normalisation layer |
| Tech stack decisions | Engineering | Must be justified and documented |
| Open questions resolved | Client | Second courier timeline, out-of-order frequency, grace window |
| Legal retention confirmation | Legal & Compliance | 30-day raw / 1-year audit must be confirmed as firm requirement |
