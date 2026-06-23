# Code Layers

---

### /controller

HTTP endpoints — the API surface of the service. Receives incoming requests, delegates to the service layer, and returns responses. No business logic lives here.

| Controller | Endpoint | Purpose |
|------------|----------|---------|
| `ShipmentEventController` | `POST /api/v1/shipments/events` | Receives events from couriers (single or batch, auto-detected) |
| `ShipmentStatusController` | `GET /api/v1/shipments/{shipmentId}/status` | Query current state of a shipment |
| `ShipmentAuditController` | `GET /api/v1/shipments/{shipmentId}/audit` | Query audit trail |
| `ShipmentEventHistoryController` | `GET /api/v1/shipments/{shipmentId}/events` | Query event history |
| `HealthController` | `GET /health` | Liveness check |

---

### /service

Business logic — orchestrates the event processing pipeline and exposes query operations. All transactional boundaries live here.

**`ShipmentEventService`** — the main service. Coordinates the full event processing flow:
1. Check for duplicate in `raw_events`
2. Persist to `raw_events` (as-received)
3. Resolve state transition via `ShipmentStateResolver`
4. Write audit log entry
5. If accepted: persist to `derived_events` and update `shipment_current_state`

Also handles queries: `getShipmentStatus`, `getEventHistory`, `getAuditLog`.

**`RetentionCleanupService`** — scheduled job that enforces legal retention policy:
- `purgeRawEvents()`: deletes `raw_events` older than 30 days, except for terminal-state shipments. Runs daily at 2 AM.
- `purgeAuditLog()`: deletes `audit_log` entries older than 1 year, except for terminal-state shipments. Runs daily at 4 AM.

---

### /domain

Pure business rules — no persistence, no infrastructure, no external dependencies.

**`ShipmentStatus`** — enum of all valid status values and the allowed transition map. Encodes:
- The canonical status values: `LABEL_CREATED`, `HANDED_TO_CARRIER`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `DELIVERY_EXCEPTION`, `RETURNED`
- `ALLOWED_TRANSITIONS` map: which transitions are valid (e.g., `IN_TRANSIT → OUT_FOR_DELIVERY` is allowed, `IN_TRANSIT → DELIVERED` is not)
- `canTransitionTo()` and `isValidTransition()`: transition validation logic

---

### /entity

JPA entities — Java classes mapped to database tables via Hibernate ORM. Each entity corresponds to a table.

| Entity | Table | Purpose |
|--------|-------|---------|
| `RawEventEntity` | `raw_events` | Every incoming event as-received. 30-day retention. Unique constraint on `(event_id, partner)` for deduplication at DB level. |
| `ShipmentEventEntity` | `derived_events` | Canonical events that passed validation and deduplication. 1-year retention. |
| `ShipmentCurrentStateEntity` | `shipment_current_state` | One row per shipment — current status, timestamps, location. Updated only on valid newer events. |
| `AuditLogEntity` | `audit_log` | Every resolution decision — `ACCEPTED`, `REJECTED`, or `NO_UPDATE` — with previous/new status and rejection reason. 1-year retention. |

Key design: `raw_events` is the deduplication gate; `derived_events` has no uniqueness constraint; `audit_log` records all decisions, not just state changes.

---

### /dto

Data Transfer Objects — plain Java classes that carry data across the API boundary. Distinct from entities: entities map to database tables, DTOs map to API payloads.

| DTO | Direction | Purpose |
|-----|-----------|---------|
| `ShipmentEventRequest` | In | Incoming event from courier |
| `EventIngestionResponse` | Out | Result of single event ingestion |
| `BatchEventRequest` | In | Batch of events from Partner B |
| `BatchEventResponse` | Out | Aggregated result for a batch |
| `ShipmentStatusResponse` | Out | Current state query |
| `ShipmentEventResponse` | Out | Event history query |
| `AuditLogResponse` | Out | Audit trail query |

Controllers receive DTOs, convert to entities, pass to services, convert back to DTOs for responses.

---

### /repository

Spring Data JPA interfaces — the data access layer. Define the interface between Java code and the SQLite database. SQL is generated from method names via Spring Data JPA's query derivation.

| Repository | Key methods |
|------------|------------|
| `RawEventRepository` | `existsByEventIdAndPartner` (dedup check); `findByReceivedAtBefore` (retention cleanup) |
| `ShipmentEventRepository` | `findByShipmentIdOrderByReceivedAtAsc` (event history); `existsByEventIdAndPartner` |
| `ShipmentCurrentStateRepository` | `findByUpdatedAtBefore` (cleanup) |
| `AuditLogRepository` | `findByShipmentIdOrderByCreatedAtAsc` (audit trail); `findByCreatedAtBefore` (cleanup) |

All extend `JpaRepository` — CRUD, pagination, and transactions are inherited. The query methods are derived automatically from method names (e.g., `existsByEventIdAndPartner` → `WHERE event_id = ? AND partner = ?`).

**If PostgreSQL replaces SQLite:** The repository interfaces and method names do not change. Spring Data JPA generates queries from method names regardless of the underlying database. What changes: Hibernate dialect (`SQLiteDialect` → `PostgreSQLDialect`), connection pool configuration, and any raw SQL using SQLite-specific functions. The service layer is unaffected.

---

### /service (resolvers)

**`ShipmentStateResolver`** — pluggable interface for custom state resolution logic. Single method: `resolve(incoming, current) → ShipmentResolutionResult`.

**`DefaultShipmentStateResolver`** — the default implementation. Applies rules in order:
1. No current state → any starting status is valid
2. Out-of-order check: if incoming `receivedAt` is earlier than current state's `lastReceivedAt` → `NO_UPDATE`
3. Transition validation: if `!isValidTransition(current, incoming)` → `REJECTED`
4. Valid transition → `ACCEPTED`, update state

The interface allows custom resolvers per partner without modifying the core pipeline.

**Adding a custom resolver:** Implement `ShipmentStateResolver`, annotate with `@Component`, and either mark it `@Primary` to make it the default, or inject it by name where needed. `ShipmentEventService` holds the interface — it doesn't know which implementation is active.

**When to add a custom resolver:**
- A partner needs different transition rules
- A grace window needs different release logic per partner
- Correction workflow changes resolution for certain states

**Small logic changes** — edit `DefaultShipmentStateResolver` and its tests directly. No new class needed.

---

## DDD Alternative Structure

The codebase above is organised by **technical layer** — all controllers, all services, all entities in their own packages. This is the standard Spring Boot layered architecture and is appropriate for a thin slice.

**DDD (Domain-Driven Design)** organises code by **domain concept** instead. The package structure reflects the business domain, not the technical role of each class.

```
/presentation
  /controllers  ─ HTTP endpoints
  /dto         ─ API contracts only

/application
  /commands     ─ write use cases (CQRS)
  /queries      ─ read use cases

/domain
  /model        ─ Aggregates, entities, value objects
  /events       ─ Domain events
  /services     ─ Domain services (e.g. ShipmentResolver)
  /repositories ─ Repository interfaces (contracts)

/infrastructure
  /persistence  ─ Repository implementations
  /messaging    ─ Domain event publishing
```

**Key shifts:**

- Repository interfaces move into `/domain` — the domain declares what it needs, infrastructure implements it
- `Shipment` becomes an **aggregate root** — it owns its state and enforces its own rules internally, rather than having state passed in and modified by external services
- Audit trail becomes **domain events** (`ShipmentReceived`, `StatusChanged`, `EventRejected`) published to a bus rather than written directly by the service
- Application service (`ShipmentEventService` today) becomes `/application` — orchestrating aggregates but containing no business logic

**Still Spring Boot** — DDD is an architectural pattern, not a technology. Spring Boot bootstraps everything. The framework doesn't care whether `Shipment` lives in `/entity/` or `/domain/model/`.

**Is it worth it here?** Not for a thin slice with one bounded context. DDD pays off when domain complexity grows — multiple aggregates, complex invariants, team boundaries. See [ARCHITECTURE.md](ARCHITECTURE.md) for the full DDD alternative architecture including the aggregate model and component interaction diagram.
