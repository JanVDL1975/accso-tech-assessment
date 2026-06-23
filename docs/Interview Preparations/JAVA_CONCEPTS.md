# Java Concepts to Revise

These are the Java concepts and patterns visible in the codebase, grouped by how likely they are to come up in a technical interview.

---

## 1. Enum with embedded transition map

`ShipmentStatus` encodes the entire state machine as a `static final Map`:

```java
private static final Map<ShipmentStatus, Set<ShipmentStatus>> ALLOWED_TRANSITIONS = Map.of(
    LABEL_CREATED, Set.of(HANDED_TO_CARRIER),
    HANDED_TO_CARRIER, Set.of(IN_TRANSIT),
    IN_TRANSIT, Set.of(OUT_FOR_DELIVERY, DELIVERY_EXCEPTION),
    OUT_FOR_DELIVERY, Set.of(DELIVERED, DELIVERY_EXCEPTION),
    DELIVERY_EXCEPTION, Set.of(IN_TRANSIT, RETURNED)
);
```

**Why this pattern.** Enums with behaviour are preferable to status-code integers or strings. The transition map is computed once at class load time — no storage overhead per instance, no lookups against an external config file. `Map.of` creates an immutable map, which is safe from accidental modification.

**What to revise:**
- `Map.of` and `Set.of` for small fixed collections (zero to ten entries) — unmodifiable, created inline
- `Set<ShipmentStatus>` as a return type and parameter type
- `allowed.contains(target)` — O(1) lookup in a hash set
- `EnumType.STRING` in JPA — stores the enum name as a varchar rather than an ordinal

---

## 2. Lombok

The codebase uses Lombok extensively across entities, DTOs, and services.

**What it generates for each annotation:**

| Annotation | Generates |
|------------|-----------|
| `@Data` | getters, setters, `equals`, `hashCode`, `toString`, `requiredArgsConstructor` |
| `@Builder` | builder pattern (`ShipmentStatusResponse.builder()...build()`) |
| `@NoArgsConstructor` | no-arg constructor |
| `@AllArgsConstructor` | all-args constructor |
| `@RequiredArgsConstructor` | constructor for `final` fields only |
| `@Slf4j` | `Logger log = LoggerFactory.getLogger(...)` |

**In the service:**
```java
@Service
@RequiredArgsConstructor
public class ShipmentEventService {
    private final RawEventRepository rawEventRepository;
    private final ShipmentEventRepository eventRepository;
    ...
}
```

`@RequiredArgsConstructor` generates a constructor that takes exactly the `final` fields — this is how Spring injects the repositories without needing `@Autowired` on each field.

**Where to be careful with Lombok:**
- `@Data` on entities: generates `equals`/`hashCode` using all fields, which can break JPA entity identity (use `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` on JPA entities instead)
- `@Builder` on entities: the default no-args constructor is still needed for JPA — `@NoArgsConstructor` and `@AllArgsConstructor` are also present
- `lombok.config` can override global settings — check for one in the project root

---

## 3. Spring Data JPA — derived query methods

```java
public interface RawEventRepository extends JpaRepository<RawEventEntity, Long> {
    boolean existsByEventIdAndPartner(String eventId, String partner);
    List<RawEventEntity> findByReceivedAtBefore(Instant cutoff, Pageable pageable);
}
```

Spring Data parses the method name and generates the SQL at startup:

- `existsByEventIdAndPartner` → `SELECT CASE WHEN count(*) > 0 THEN true ELSE false END WHERE event_id = ? AND partner = ?`
- `findByReceivedAtBefore...Pageable` → `SELECT * WHERE received_at < ? LIMIT ? OFFSET ?`

**Naming convention breakdown:**
```
findBy                   — SELECT
ReceivedAtBefore         — WHERE received_at < ?
Pageable                 — LIMIT/OFFSET applied
```

Spring Data supports `And`, `Or`, `GreaterThan`, `LessThan`, `In`, `Containing`, `StartingWith`, `Between`, and many more. For anything not expressible through naming conventions, use `@Query` with JPQL or native SQL.

**Pagination:**
```java
List<RawEventEntity> findByReceivedAtBefore(Instant cutoff, Pageable pageable);
```
Passed to the cleanup service as:
```java
Pageable pageable = PageRequest.of(0, 500);
```
Fetch 500 records at a time to avoid loading the entire table into memory during cleanup.

---

## 4. Interface-based design — the Strategy pattern

```java
public interface ShipmentStateResolver {
    ShipmentResolutionResult resolve(ShipmentEventEntity incoming, ShipmentCurrentStateEntity current);
}

@Component
public class DefaultShipmentStateResolver implements ShipmentStateResolver { ... }
```

**Why an interface for a single implementation.** It decouples the caller from the implementation — `ShipmentEventService` depends only on the interface. Adding a partner-specific resolver (e.g., `PartnerBStateResolver`) requires no change to `ShipmentEventService`. The interface also makes testing easier — a mock resolver can be injected without the full implementation.

This is the Strategy pattern: pluggable algorithms behind a common interface. Spring makes this trivial to use — any bean implementing `ShipmentStateResolver` is eligible for injection wherever the interface is required.

---

## 5. `@Transactional` — what it actually does

```java
@Transactional
public EventIngestionResponse receiveEvent(ShipmentEventRequest request) {
    // Step 1: Duplicate check
    if (rawEventRepository.existsByEventIdAndPartner(eventId, partner)) { ... }

    // Step 2: Persist to raw_events
    rawEventRepository.save(rawEvent);

    // Step 3: Resolve state transition

    // Step 4: Write audit_log entry

    // Step 5: If accepted: persist to derived_events and update current state
    if (result.getNewStatus() != null) {
        updateCurrentState(request.getShipmentId(), result);
    }
}
```

**What `@Transactional` buys you here.** Spring proxies the class — before the method runs, a transaction starts; when the method exits normally, the transaction commits; if an exception is thrown, the transaction rolls back. All five steps happen in one atomic unit.

**What to be careful about:**
- `@Transactional` on a method called from *within the same class* bypasses the proxy — the call is direct, not through the proxy, so the transaction never starts. `receiveEvent` calls `receiveEvent` recursively in `receiveBatchEvents` — but that call chain is not transactional at the batch level (each individual call has its own transaction from the outer `receiveEvent`).
- **Read-only transactions:** `@Transactional(readOnly = true)` on `getShipmentStatus` and `getEventHistory` lets the JPA provider and database driver optimise — the driver may skip the transaction log, and JPA's first-level cache behaves differently.

**The failure window.** If the service crashes after step 2 (`raw_events` written) but before step 5 (current state updated), the transaction rolls back on restart — but only the in-memory state is rolled back, not the already-committed `raw_events` write. This is why `derived_events` and `current_state` can temporarily diverge after a crash.

---

## 6. Immutable value objects — `ShipmentResolutionResult`

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentResolutionResult {
    private boolean accepted;
    private ShipmentStatus newStatus;
    private Instant newLastOccurredAt;
    private Instant newLastReceivedAt;
    private String newLocation;
    private String rejectionReason;
}
```

Despite `@Data` generating setters, the result objects are used as immutable value objects — callers read fields but never modify a result after construction. The static factory methods enforce this:

```java
public static ShipmentResolutionResult accepted(...) {
    return ShipmentResolutionResult.builder()
            .accepted(true)
            .newStatus(status)
            ...
            .build();
}

public static ShipmentResolutionResult noUpdate() { ... }
public static ShipmentResolutionResult rejected(String reason) { ... }
```

**Why not make it truly immutable?** `@Value` (Lombok) creates a class with all fields `final`, no setters, and a private constructor — it is the immutable equivalent of `@Data`. The codebase uses `@Data @Builder` instead, which is a pragmatic choice: the builder is convenient for test data creation, and the codebase convention is consistent.

---

## 7. `@PrePersist` — entity lifecycle callback

```java
@PrePersist
protected void onCreate() {
    if (createdAt == null) {
        createdAt = Instant.now();
    }
}
```

`@PrePersist` fires before JPA writes the entity to the database for the first time. This is the JPA equivalent of a database `DEFAULT` clause — but in Java code, portable across database dialects.

**Why not just set it in the service?** Because `createdAt` is also set explicitly in `ShipmentEventService`:
```java
eventEntity.setCreatedAt(Instant.now());
eventRepository.save(eventEntity);
```
The `@PrePersist` is a backstop in case the service forgets to set it. This is defensive layering — two places that do the same thing, so if one is missing the other still works.

---

## 8. `Optional` — `orElse(null)` vs `orElseThrow()`

```java
Optional<ShipmentCurrentStateEntity> currentStateOpt =
        currentStateRepository.findById(request.getShipmentId());
ShipmentCurrentStateEntity currentState = currentStateOpt.orElse(null);
```

`findById` returns `Optional<T>` — empty if no row exists, present if found. The `orElse(null)` pattern is common but has a cost: every caller must decide how to handle null. A more explicit alternative is `orElseThrow()`:

```java
ShipmentCurrentStateEntity currentState = currentStateOpt
    .orElseThrow(() -> new ShipmentNotFoundException(shipmentId));
```

For the resolver, `orElse(null)` is deliberate — a null current state means "first event for this shipment" and is handled explicitly:

```java
if (current == null) {
    return ShipmentResolutionResult.accepted(incomingStatus, ...);
}
```

**What to revise:**
- `Optional.map()`, `Optional.filter()`, `Optional.flatMap()` for chaining transformations
- `Optional.isPresent()` / `ifPresent()` vs pattern matching with `orElse()` / `orElseThrow()`
- `Optional.ofNullable()` for wrapping a potentially-null reference

---

## 9. Stream API — `.toList()` and chaining

```java
public BatchEventResponse receiveBatchEvents(List<ShipmentEventRequest> events) {
    List<EventIngestionResponse> results = events.stream()
            .map(this::receiveEvent)
            .toList();   // Java 16+ immutable List
    return BatchEventResponse.of(results);
}
```

`.toList()` (Java 16+) is preferred over `.collect(Collectors.toList())` — it produces an unmodifiable `List` directly without an intermediate collector object.

**In query methods:**
```java
return eventRepository.findByShipmentIdOrderByReceivedAtAsc(shipmentId)
        .stream()
        .map(event -> ShipmentEventResponse.builder()
                .eventId(event.getEventId())
                ...
                .build())
        .toList();
```

**Common stream pitfalls to revise:**
- `.map()` vs `.flatMap()` — `map` transforms one element to one element; `flatMap` transforms one element to a stream of zero or more elements
- `.filter()` with null checks — `.filter(Objects::nonNull)` to remove nulls
- `.reduce()` — terminal operation; not always the clearest option for simple aggregations
- Side effects in streams — `.forEach()` vs collecting; `.forEach()` is fine for logging, not for mutating shared state

---

## 10. `Instant` — UTC timestamps

```java
private Instant lastOccurredAt;
private Instant lastReceivedAt;
private Instant updatedAt;
private Instant createdAt;
```

`Instant` represents a point in time on the UTC timeline — seconds and nanoseconds since the epoch (1 January 1970). It has no time zone and no calendar system.

**Key distinctions:**
- `Instant` — a point in time, timezone-neutral. Use for storage and computation.
- `LocalDateTime` — a date and time with no zone. Use for display in a specific zone only after applying a time zone.
- `ZonedDateTime` — a date and time with an explicit zone. Use when you need both the instant and the zone together.

The codebase stores `Instant` everywhere and converts to/from strings in the DTO layer. This is correct — the database holds UTC, the API accepts UTC strings, and no time zone ambiguity can arise.

**For the interview:** "If I store `Instant.now()` from a server in London and read it from a server in Tokyo, do I get the same value?" Answer: yes, because `Instant` is absolute, not zoned. If you stored `LocalDateTime.now()` instead, you would get the local time in each zone — different values for the same instant.

---

## 11. `@Entity` and JPA basics

```java
@Entity
@Table(name = "shipment_current_state")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentCurrentStateEntity {
    @Id
    @Column(name = "shipment_id")
    private String shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false)
    private ShipmentStatus currentStatus;
    ...
}
```

**Key JPA concepts visible:**

- `@Id` — primary key. `String shipmentId` is the natural key; no `@GeneratedValue` because the ID comes from the courier partner, not the database.
- `@Column(name = "...")` — maps field name to database column name. Required when the Java field name differs from the SQL column name.
- `@Enumerated(EnumType.STRING)` — stores enum as varchar of the enum name. The alternative `EnumType.ORDINAL` stores the integer ordinal — fragile if enum order changes.
- `nullable = false` — column constraint. JPA validates this before generating SQL.
- `@Table(name = "...")` — explicitly names the table. Without it, JPA uses the class name in snake_case by default.

**For the interview:** "What happens if two threads call `rawEventRepository.save()` for the same event ID simultaneously?" Answer: the unique constraint on `(event_id, partner)` in the database throws a `DataIntegrityViolationException`. Spring's transaction boundary means the exception propagates out and the transaction rolls back. This is why the database constraint is the backstop even if the code-level check passes.

---

## 12. Functional interfaces — static factory methods as constructors

```java
public static ShipmentResolutionResult accepted(
        ShipmentStatus status, Instant occurredAt, Instant receivedAt, String location) {
    return ShipmentResolutionResult.builder()
            .accepted(true)
            .newStatus(status)
            .newLastOccurredAt(occurredAt)
            .newLastReceivedAt(receivedAt)
            .newLocation(location)
            .build();
}
```

These are not true factory methods in the pattern-sense — they don't decide which subclass to return. They are named constructors that convey intent and provide a clearer API than the builder alone:

```java
// Builder is verbose and lets you get fields wrong
ShipmentResolutionResult r1 = ShipmentResolutionResult.builder()
    .accepted(true)   // wrong — accepted but newStatus is null
    .newStatus(status)
    ...
    .build();

// Static factory method is explicit and guards against common mistakes
ShipmentResolutionResult r2 = ShipmentResolutionResult.accepted(status, occurredAt, receivedAt, location);
```

The builder is still used internally (`@Builder` on the class), which is the common Lombok pattern: expose a clean factory API, keep the builder for internal use and test data.

---

## Summary of what to focus on

| Concept | Where it appears | Interview weight |
|---------|-----------------|-----------------|
| Enum with transition map | `ShipmentStatus` | High |
| `@Transactional` failure windows | `ShipmentEventService` | High |
| Interface-based / Strategy pattern | `ShipmentStateResolver` | Medium |
| Spring Data JPA derived queries | `RawEventRepository` | Medium |
| `Optional` handling | `receiveEvent` | Medium |
| Immutable value objects | `ShipmentResolutionResult` | Medium |
| `Instant` vs `LocalDateTime` | All timestamp fields | Medium |
| `@PrePersist` lifecycle | `ShipmentEventEntity` | Low |
| Lombok what/why | All entities and DTOs | Low |
| Stream `.toList()` | `receiveBatchEvents` | Low |

























