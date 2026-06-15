# Overview

## Project Context

The client runs an e-commerce platform that ships orders through a courier partner. A recurring integrity problem exists: downstream systems disagree about shipment state because courier events arrive late, out of order, duplicated, or with conflicting data. Customer support, order tracking, and incident response need a trustworthy view of what happened and what the current shipment status is.

## Goals

1. Provide a reliable, authoritative current view of each shipment
2. Maintain a queryable history of all shipment events and how the current state was derived
3. Handle the core data integrity challenges: duplicates, out-of-order arrival, and conflicting updates
4. Deliver a pragmatic, phased solution that can ship to production incrementally

## Scope

### In Scope

- Technical strategy memo for client engineering lead
- Phased delivery plan with risk register
- One thin executable slice demonstrating the approach
- Two architecture decision records (ADRs)
- Development process notes covering AI tooling usage

### Out of Scope

- Complete platform build
- Full CI/CD infrastructure just for show
- Feature breadth beyond data integrity

## High-Level Requirements

### Core Functionality

- **Event ingestion**: Receive shipment events from a courier partner via webhook
- **Normalization**: Standardize events from different partners into a common schema
- **Deduplication**: Detect and collapse duplicate events based on eventId
- **Ordering**: Handle out-of-order arrival using `occurredAt` timestamps
- **Conflict resolution**: When events conflict, apply a deterministic rules engine to derive the authoritative state
- **Current state derivation**: Maintain a reliable, queryable current state per shipment
- **Audit trail**: Store the full event history and the decisions made to derive state

### Suggested Status Values

`LABEL_CREATED` → `HANDED_TO_CARRIER` → `IN_TRANSIT` → `OUT_FOR_DELIVERY` → `DELIVERED`
Exception path: `DELIVERY_EXCEPTION` → `RETURNED`

## Example Inbound Event Schema

```json
{
  "eventId": "evt-123",
  "partner": "dhl",
  "shipmentId": "ship-456",
  "status": "IN_TRANSIT",
  "occurredAt": "2026-03-10T12:00:00Z",
  "receivedAt": "2026-03-10T12:00:05Z",
  "location": "Amsterdam"
}
```

## Deliverables

1. **Technical strategy memo** (3–5 pages): Problem framing, assumptions, architecture, data integrity strategy, operational concerns
2. **Delivery plan**: Phased approach, minimum credible first slice, risk register, success signals
3. **Executable slice**: One small but meaningful piece (e.g. event normalization, deduplication, state derivation, or conflict resolution) - executable and testable
4. **Two ADRs**: Covering the most important strategic decisions
5. **Development process note**: How AI tools were used, what was overridden or verified, and at least one concrete example where judgment differed from AI
