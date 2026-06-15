# Change Request: Batch Ingestion, Retention Cleanup, and Partner B Onboarding Prep

**Date:** 2026-06-15
**Status:** Draft
**Related PR:** dev → main

---

## 1. Summary

This change introduces batch event ingestion, legal retention cleanup, and architectural foundations to support a second courier partner (Partner B) onboarding within one quarter.

The service now accepts both a single event and a batch of events at the same endpoint, persists raw partner payloads for 30 days (legal requirement), maintains an audit decision log for 1 year (legal requirement), and runs scheduled cleanup jobs to enforce both retention windows.

---

## 2. Problem Statement

The original Phase 1 scope was single-event ingestion only, with no retention cleanup. The client has since confirmed:

1. **Partner B onboarding within one quarter** — Partner B sends batched events (not one at a time) and frequently sends events out of order.
2. **Legal retention requirements are firm:**
   - Raw partner payloads must be deleted after 30 days
   - Audit decisions must remain queryable for 1 year

These requirements were not in the original scope and must be addressed before Partner B goes live.

---

## 3. Changes

### 3.1 Batch Ingestion

**`POST /api/v1/shipments/events`** now accepts both a single event object and a bare array of events. The format is auto-detected at runtime:

- **Single event:** `{"eventId": "...", "partner": "...", ...}` → `EventIngestionResponse`
- **Batch (array):** `[{"eventId": "...", ...}, {...}]` → `BatchEventResponse`

Each event in a batch is processed independently. One bad event does not poison the batch. The response reports accepted, rejected, and duplicate counts.

### 3.2 Raw Event Store and Audit Log

Three event stores are maintained:

| Table | Retention | Description |
|-------|-----------|-------------|
| `raw_events` | 30 days | Raw partner payloads, as-received |
| `derived_events` | Indefinite | Canonical events that passed validation |
| `audit_log` | 1 year | Every resolution decision with rationale |

Terminal-state shipments (`DELIVERED`, `RETURNED`) are exempt from both cleanup schedules.

### 3.3 Retention Cleanup

Scheduled jobs enforce the legal retention windows:

- **Raw event purge:** Daily at 2:00 AM — deletes `raw_events` older than 30 days (terminal-state shipments exempt)
- **Audit log purge:** Daily at 4:00 AM — deletes `audit_log` entries older than 1 year (terminal-state shipments exempt)

### 3.4 Ordering Authority

`receivedAt` is used as the authoritative timestamp for state derivation (updated from `occurredAt` in ADR-001). `occurredAt` is stored for audit but not used for ordering decisions.

### 3.5 Documentation

- `docs/API.md` — Full API documentation for all endpoints
- `docs/TECHNICAL_STRATEGY_MEMO.md` — Updated with batch processing, retention, Partner B context
- `docs/DELIVERY_PLAN.md` — Updated scope and success signals
- `docs/RISK_REGISTER.md` — Added R8–R11 for Partner B and retention risks
- `docs/ADR.md` — ADR-001 corrected to `receivedAt`; ADR-005 (batch isolation) and ADR-006 (split retention) added

---

## 4. Scope Decisions

### In Scope (This PR)

- Batch ingestion (bare array, auto-detected)
- Raw event store with 30-day retention
- Audit decision log with 1-year retention
- Scheduled retention cleanup jobs
- Full API documentation
- Updated architecture and risk documents

### Out of Scope (Phase 2)

- Partner-specific normalisation layer (Partner B payload mapping)
- Grace window for out-of-order events
- Multi-partner deduplication context
- Metrics, alerting, and observability
- Production deployment and hosting model

---

## 5. Open Questions (For Client)

1. **Partner B timeline** — Is one quarter confirmed or aspirational? What does "onboarded" mean — generic batch ingestion or full partner-specific integration?
2. **Out-of-order frequency** — What percentage of Partner B's events arrive out of order? Do you expect us to handle it silently or flag it?
3. **Grace window** — Do you need a grace window (e.g., hold events for 5 minutes) before applying them? Without it, many of Partner B's out-of-order events will arrive but not update state.
4. **Legal retention confirmation** — Are the 30-day raw / 1-year audit schedules confirmed as firm legal requirements?

---

## 6. Risks Addressed

| Risk | Mitigation |
|------|------------|
| R8: Partner B sends fundamentally different event shapes | Generic resolver handles all events; partner-specific logic deferred to Phase 2 |
| R9: Retention cleanup deletes events in dispute | Terminal-state shipments exempt from cleanup |
| R10: Partner B frequently sends events out of order | Grace window not implemented in Phase 1 — deferred for calibration based on observed data |
| R11: Audit log grows unbounded for active shipments | Monitor growth rate; consider snapshot compaction in Phase 2 |

---

## 7. Files Changed

**New files:**
- `src/main/java/.../entity/RawEventEntity.java`
- `src/main/java/.../entity/AuditLogEntity.java`
- `src/main/java/.../repository/RawEventRepository.java`
- `src/main/java/.../repository/AuditLogRepository.java`
- `src/main/java/.../dto/AuditLogResponse.java`
- `src/main/java/.../dto/BatchEventRequest.java`
- `src/main/java/.../dto/BatchEventResponse.java`
- `src/main/java/.../controller/ShipmentAuditController.java`
- `src/main/java/.../service/RetentionCleanupService.java`
- `docs/API.md`

**Modified files:**
- `src/main/java/.../controller/ShipmentEventController.java` — single endpoint, auto-detects single/batch
- `src/main/java/.../service/ShipmentEventService.java` — raw event write, audit log write, batch processing
- `src/main/java/.../ShipmentIntegrityServiceApplication.java` — re-added `@EnableScheduling`
- `src/main/resources/application.yml` — retention config
- `src/test/.../ShipmentIntegrationTest.java` — added batch and audit tests
- `docs/TECHNICAL_STRATEGY_MEMO.md`
- `docs/DELIVERY_PLAN.md`
- `docs/RISK_REGISTER.md`
- `docs/ADR.md`

**Deleted files:** None (no files removed in this change)

---

## 8. Test Coverage

- 28 tests, all passing
- `ShipmentIntegrationTest` — 11 tests covering single event, batch, duplicate, status, history, audit
- `ShipmentStatusTest` — 11 tests covering all status transitions
- `DefaultShipmentStateResolverTest` — 6 tests covering resolver logic
