# Sequence Diagrams

**Date:** 2026-06-15

---

## Event Ingestion

```mermaid
sequenceDiagram
    participant P as Partner
    participant IE as Ingestion Endpoint
    participant N as Normaliser
    participant D as Deduplication Check
    participant RE as Resolution Engine
    participant ES as Event Store
    participant CS as Current State
    participant AT as Audit Trail

    P->>IE: POST shipment event (partner format)
    IE->>N: Normalise to canonical model
    N->>D: Check (eventId, partner) uniqueness
    D-->>N: already exists?

    alt duplicate
        N->>AT: Log REJECTED / DUPLICATE
        N-->>IE: EventIngestionResponse.duplicate()
        IE-->>P: 200 OK (duplicate)
    else not duplicate
        N->>RE: Resolve(incoming event, currentState)
        RE-->>N: Outcome (accept / reject / noUpdate)

        alt accept + state change
            RE->>ES: Append event to store
            RE->>CS: Upsert current state
            RE->>AT: Log ACCEPTED
        else accept, no state change
            RE->>ES: Append event to store
            RE->>AT: Log NO_UPDATE
        else reject
            RE->>AT: Log REJECTED + reason
        end

        N-->>IE: EventIngestionResponse
        IE-->>P: 200 OK
    end
```

---

## Resolution Decision Logic

```mermaid
flowchart TD
    A["Resolution Engine"] --> B{Current state exists?}

    B -->|NO| C["ACCEPT<br/>Any starting status is valid"]
    B -->|YES| D{"Is incoming.receivedAt <<br/>older than current.lastReceivedAt?"}

    D -->|YES| E["NO_UPDATE<br/>Event is out-of-order:<br/>older than known state"]
    D -->|NO| F{Is transition valid<br/>currentStatus → incomingStatus?}

    F -->|NO| G["REJECT<br/>Invalid transition"]
    F -->|YES| H["ACCEPT<br/>New status applied"]
```

---

## Status Transitions

```mermaid
stateDiagram-v2
    [*] --> LABEL_CREATED
    LABEL_CREATED --> HANDED_TO_CARRIER
    HANDED_TO_CARRIER --> IN_TRANSIT
    IN_TRANSIT --> OUT_FOR_DELIVERY
    IN_TRANSIT --> DELIVERY_EXCEPTION
    OUT_FOR_DELIVERY --> DELIVERED
    OUT_FOR_DELIVERY --> DELIVERY_EXCEPTION
    DELIVERY_EXCEPTION --> IN_TRANSIT
    DELIVERY_EXCEPTION --> RETURNED
    DELIVERED --> [*]
    RETURNED --> [*]
```

---

## Query APIs

### Get Current Status

```mermaid
sequenceDiagram
    participant Cl as Client
    participant QA as Query API
    participant CS as Current State

    Cl->>QA: GET /shipments/{shipmentId}/status
    QA->>CS: Find current state
    CS-->>QA: CurrentState

    alt shipment known
        QA-->>Cl: 200 OK + status, location, lastUpdated
    else unknown
        QA-->>Cl: 404 Not Found
    end
```

### Get Event History

```mermaid
sequenceDiagram
    participant Cl as Client
    participant QA as Query API
    participant ES as Event Store

    Cl->>QA: GET /shipments/{shipmentId}/events
    QA->>ES: Query events by shipmentId
    QA-->>Cl: 200 OK + event list (ordered by receivedAt)
```

### Get Audit Log

```mermaid
sequenceDiagram
    participant Cl as Client
    participant QA as Query API
    participant AT as Audit Trail

    Cl->>QA: GET /shipments/{shipmentId}/audit
    QA->>AT: Query audit entries by shipmentId
    QA-->>Cl: 200 OK + decision log (oldest first)
```