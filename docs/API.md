# API Documentation

**Base URL:** `http://localhost:8080`

---

## Endpoints

### POST `/api/v1/shipments/events`

Receives shipment events from courier partners. Accepts both a single event and a batch of events. The format is auto-detected based on the JSON structure.

**Single event request body:**
```json
{
  "eventId": "evt-123",
  "partner": "dhl",
  "shipmentId": "ship-456",
  "status": "LABEL_CREATED",
  "occurredAt": "2026-03-10T12:00:00Z",
  "receivedAt": "2026-03-10T12:00:05Z",
  "location": "Amsterdam"
}
```

**Batch request body (bare array):**
```json
[
  {
    "eventId": "evt-1",
    "partner": "dhl",
    "shipmentId": "ship-456",
    "status": "LABEL_CREATED",
    "occurredAt": "2026-03-10T12:00:00Z",
    "receivedAt": "2026-03-10T12:00:05Z"
  },
  {
    "eventId": "evt-2",
    "partner": "dhl",
    "shipmentId": "ship-456",
    "status": "HANDED_TO_CARRIER",
    "occurredAt": "2026-03-10T13:00:00Z",
    "receivedAt": "2026-03-10T13:00:05Z"
  }
]
```

**Single event response:**
```json
{ "accepted": true, "eventId": "evt-123" }
```

**Duplicate response:**
```json
{ "accepted": false, "eventId": "evt-123", "reason": "DUPLICATE_EVENT" }
```

**Rejected response:**
```json
{ "accepted": false, "eventId": "evt-123", "reason": "INVALID_TRANSITION" }
```

**Batch response:**
```json
{
  "totalReceived": 2,
  "acceptedCount": 2,
  "rejectedCount": 0,
  "duplicateCount": 0,
  "results": [
    { "accepted": true, "eventId": "evt-1" },
    { "accepted": true, "eventId": "evt-2" }
  ]
}
```

---

### GET `/api/v1/shipments/{shipmentId}/status`

Returns the current state of a shipment.

**Response (200 OK):**
```json
{
  "shipmentId": "ship-456",
  "currentStatus": "IN_TRANSIT",
  "lastReceivedAt": "2026-03-10T14:00:00Z",
  "location": "Berlin"
}
```

**Response (404 Not Found):** Shipment not found.

---

### GET `/api/v1/shipments/{shipmentId}/events`

Returns the full event history for a shipment, ordered by `receivedAt` ascending. Only accepted events are stored.

**Response (200 OK):**
```json
[
  {
    "eventId": "evt-1",
    "shipmentId": "ship-456",
    "partner": "dhl",
    "status": "LABEL_CREATED",
    "occurredAt": "2026-03-10T12:00:00Z",
    "receivedAt": "2026-03-10T12:00:05Z",
    "location": "Amsterdam"
  },
  {
    "eventId": "evt-2",
    "shipmentId": "ship-456",
    "partner": "dhl",
    "status": "HANDED_TO_CARRIER",
    "occurredAt": "2026-03-10T13:00:00Z",
    "receivedAt": "2026-03-10T13:00:05Z"
  }
]
```

**Response (200 OK, empty):** `[]`

---

### GET `/api/v1/shipments/{shipmentId}/audit`

Returns the audit decision log for a shipment, ordered by `createdAt` ascending. Records every resolution decision including duplicates, rejections, and state changes.

**Response (200 OK):**
```json
[
  {
    "eventId": "evt-1",
    "shipmentId": "ship-456",
    "partner": "dhl",
    "previousStatus": null,
    "newStatus": "LABEL_CREATED",
    "decision": "ACCEPTED",
    "rejectionReason": null,
    "receivedAt": "2026-03-10T12:00:05Z",
    "createdAt": "2026-03-10T12:00:06Z"
  },
  {
    "eventId": "evt-2",
    "shipmentId": "ship-456",
    "partner": "dhl",
    "previousStatus": "LABEL_CREATED",
    "newStatus": "HANDED_TO_CARRIER",
    "decision": "ACCEPTED",
    "rejectionReason": null,
    "receivedAt": "2026-03-10T13:00:05Z",
    "createdAt": "2026-03-10T13:00:06Z"
  }
]
```

**Response (200 OK, empty):** `[]`

---

### GET `/health`

Health check endpoint.

**Response (200 OK):**
```json
{ "status": "UP" }
```

---

## Field Reference

### ShipmentEventRequest

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `eventId` | string | Yes | Partner-scoped event identifier |
| `partner` | string | Yes | Courier partner name |
| `shipmentId` | string | Yes | Shipment identifier |
| `status` | enum | Yes | Current status value |
| `occurredAt` | ISO 8601 instant | Yes | When the event happened in the partner's system |
| `receivedAt` | ISO 8601 instant | Yes | When the partner received the event |
| `location` | string | No | Location where the event occurred |

### Status Values

| Value | Description |
|-------|-------------|
| `LABEL_CREATED` | Shipping label created |
| `HANDED_TO_CARRIER` | Package handed to courier |
| `IN_TRANSIT` | In transit |
| `OUT_FOR_DELIVERY` | Out for delivery |
| `DELIVERED` | Delivered (terminal) |
| `DELIVERY_EXCEPTION` | Delivery exception occurred |
| `RETURNED` | Returned to sender (terminal) |

### Allowed Status Transitions

```
LABEL_CREATED → HANDED_TO_CARRIER → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
                                              ↘ DELIVERY_EXCEPTION ↗
                                              ↘ RETURNED ↗
DELIVERED and RETURNED are terminal — all further events are rejected
```

### AuditDecision Values

| Value | Description |
|-------|-------------|
| `ACCEPTED` | Event was valid and processed; may or may not have changed state |
| `REJECTED` | Event was rejected (duplicate, invalid transition, or terminal state) |
| `NO_UPDATE` | Event was valid but did not update current state (out-of-order) |

---

## Processing Rules

1. **Deduplication**: `(eventId, partner)` uniquely identifies an event. Duplicates are rejected with `reason="DUPLICATE_EVENT"`.

2. **Event Ordering**: `receivedAt` is used as the authoritative timestamp. `occurredAt` is stored for audit but not used for ordering.

3. **Out-of-Order Handling**: Events with older `receivedAt` than the current state are stored but do NOT update current state. History is preserved.

4. **Terminal States**: `DELIVERED` and `RETURNED` are terminal. All incoming events are rejected.

5. **Batch Processing**: Each event in a batch is processed individually. One bad event does not poison the batch. Duplicates within a batch are counted separately from rejections.
