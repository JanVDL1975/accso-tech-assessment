# Delivery Plan

**Date:** 2026-06-15  
**Status:** Updated (Reflects Implementation Reality)  
**Related Document:** TECHNICAL_STRATEGY_MEMO.md  

---

## Overview

This document defines the intended architectural phases and reflects minor scope evolution during implementation. The system was developed iteratively, and some Phase 2 capabilities were introduced earlier than originally planned.

---

## Phase 1 - Foundation (Core Shipment System)

**Objective:** Deliver a working, deterministic shipment processing system with reliable state resolution.

### Scope

- Single-partner event ingestion endpoint (HTTPS POST)
- Canonical event model and normalization
- Deduplication logic (per-partner `eventId`)
- Out-of-order event handling using `receivedAt`
- Deterministic conflict resolution rules engine (stateless)
- Shipment state derivation and persistence
- Queryable current state by `shipmentId`
- Batch ingestion (array payload support with per-event isolation)
- Terminal state enforcement (`DELIVERED`, `RETURNED`)
- Core audit logging of state transition decisions

### Success Signals

- Duplicate events do not alter shipment state
- Out-of-order events are resolved deterministically
- Conflicting events are handled consistently via rules engine
- Shipment current state is always queryable
- Batch ingestion is resilient (no single event failure corrupts batch)

---

## Phase 2 - Observability & Data Governance

**Objective:** Provide system traceability, auditability, and data lifecycle management.

### Scope

- Raw event persistence layer
  - `RawEventEntity`
  - `RawEventRepository`

- Audit trail system
  - `AuditLogEntity`
  - `AuditLogRepository`
  - `ShipmentAuditController`

- Retention and cleanup service
  - `RetentionCleanupService`

### Goals

- Full traceability of inbound events from ingestion to state resolution
- Queryable audit history for debugging and compliance
- Automated lifecycle management of raw and derived data

---

## Implementation Note (Scope Evolution)

During implementation, several Phase 2 capabilities were introduced earlier than originally planned to support end-to-end validation and system correctness.

Specifically:

- Raw event persistence
- Audit trail logging
- Retention cleanup logic

These were implemented alongside core functionality to ensure correctness of state resolution, traceability, and testability during development.

---

## Delivered vs Intended Mapping

| Area | Intended Phase | Delivered In |
|------|---------------|--------------|
| Core ingestion + state engine | Phase 1 | Phase 1 |
| Raw event store | Phase 2 | Iterative (early delivery) |
| Audit trail system | Phase 2 | Iterative (early delivery) |
| Retention service | Phase 2 | Iterative (early delivery) |

---

## Dependencies and External Inputs

| Dependency | Owner | Notes |
|------------|-------|-------|
| Partner API contract | Courier partner | Required for normalization layer |
| Technical stack decisions | Engineering | Must remain documented and justified |
| Operational requirements | Client | Includes out-of-order tolerance expectations |
| Compliance requirements | Legal/Compliance | Retention policies (30-day raw, 1-year audit) |

---

## Notes

- The system follows an iterative delivery approach rather than strict phase isolation.
- Final implementation prioritizes correctness, traceability, and deterministic behavior over rigid phase boundaries.