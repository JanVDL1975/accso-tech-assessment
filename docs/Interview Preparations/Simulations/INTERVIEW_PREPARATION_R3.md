# Question 1 — Introduction & System Understanding

---

## Q1. Can you describe the system you designed?

**Strong Answer:**

The system is a **Shipment Integrity Service** that ingests shipment events from multiple courier partners via webhooks and maintains a **consistent, trustworthy view of shipment state**.

The core problem it solves is that courier events are unreliable in practice:
- They can arrive **out of order**
- They can be **duplicated**
- They can be **delayed**
- Sometimes they can even be **conflicting across partners**

The system ensures that despite this, we always maintain the **correct latest authoritative state of a shipment**.

At a high level, the system:
1. Receives webhook events from courier partners  
2. Validates and deduplicates them  
3. Applies ordering rules using timestamps (`receivedAt`, event time, or partner timestamp)  
4. Resolves the correct shipment state using deterministic rules  
5. Persists the final state in a database  
6. Exposes APIs so downstream systems can query shipment status reliably  

The main design goal is **correctness under unreliable input**, not just simple event storage.

---

## Q2. What problem are you solving and why is it difficult?

**Strong Answer:**

The core problem is maintaining **state consistency in a distributed, event-driven system where inputs are unreliable**.

It is difficult because courier events are:
- **At-least-once delivered** → duplicates are expected  
- **Not ordered** → IN_TRANSIT may arrive before LABEL_CREATED  
- **Not fully reliable** → some events may be missing or delayed  
- **Multi-source** → different partners may report different states for the same shipment  

So the challenge is not storing events, but answering:

> “Given messy, unordered events, what is the correct current shipment state?”

This requires:
- Idempotency  
- Deduplication  
- Ordering strategy  
- Conflict resolution logic  
- A deterministic state machine  

---

## Q3. What does an incoming event look like?

**Strong Answer:**

Each incoming webhook event typically includes:

- `partner` (e.g., DHL, FedEx)  
- `eventId` (unique per partner event)  
- `shipmentId`  
- `eventType` (e.g., LABEL_CREATED, IN_TRANSIT, DELIVERED)  
- `eventTime` (when courier says it happened)  
- `receivedAt` (when our system received it)  
- Optional metadata (location, scan details, etc.)  

The most important fields for correctness are:
- `partner + eventId` → for deduplication  
- `eventTime / receivedAt` → for ordering decisions  
- `eventType` → for state transitions  

---

## Q4. How do you handle duplicate events?

**Strong Answer:**

We handle duplicates using **idempotency at the event level**.

The system stores a unique constraint or deduplication key:

> `(partner, eventId)`

When a new event arrives:
- We first check if this key already exists in the event store  
- If it exists → we ignore it completely  
- If not → we persist it and process it  

This ensures that even if a courier retries the same webhook multiple times, it has **no effect on system correctness or state transitions**.

This is critical because webhook systems are typically **at-least-once delivery systems**.

---

## Q5. How do you deal with out-of-order events?

**Strong Answer:**

Out-of-order events are handled using a **deterministic ordering strategy**, not arrival order.

We do NOT assume events arrive in sequence.

Instead, we evaluate ordering using:
1. `eventTime` (preferred source of truth)
2. If missing or unreliable → fallback to `receivedAt`

Then we apply a rule:

> Only allow state transitions that move forward in time or follow a valid state machine path.

We also enforce a **monotonic state update rule**:
- A shipment cannot move backward in lifecycle (e.g., DELIVERED → IN_TRANSIT is ignored)
- Older events cannot override newer valid states  

This ensures late-arriving events do not corrupt the current shipment state.

---

## Q6. How do you decide the “correct” shipment state?

**Strong Answer:**

We use a combination of:
1. **Finite state machine (FSM)**  
2. **Event ordering rules**  
3. **Authoritativeness rules across partners**  

The FSM defines valid transitions:
- LABEL_CREATED → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED  

When multiple events exist:
- We filter invalid transitions  
- We sort remaining candidates by `eventTime`  
- We apply the latest valid state transition  

If multiple partners disagree:
- We apply a **priority rule per partner** (if defined)  
- Otherwise we fall back to “latest valid timestamp wins”  

The goal is to ensure the system is:
- deterministic  
- repeatable  
- explainable  

---

## Q7. What does your data model look like?

**Strong Answer:**

There are two main persisted concepts:

### 1. Event Store (immutable)

Stores all valid incoming events:

- partner  
- eventId  
- shipmentId  
- eventType  
- eventTime  
- receivedAt  
- payload  

This is append-only and supports auditability.

---

### 2. Shipment State Table (current snapshot)

Stores the latest known state:

- shipmentId  
- currentState  
- lastUpdatedAt  
- lastProcessedEventId  
- lastEventTime  

This table is optimized for fast reads.

---

This separation allows:
- Full audit history (event store)  
- Fast lookup (state table)  

---

## Q8. How do you ensure consistency between event processing and state updates?

**Strong Answer:**

We ensure consistency using **atomic processing per event**:

Each event processing step is wrapped in a single transaction:

1. Check deduplication key  
2. Persist event (if new)  
3. Compute new state  
4. Compare against current stored state  
5. Update shipment state if valid  

We also ensure:
- Optimistic locking on shipment state (to avoid race conditions)  
- Idempotent updates (reprocessing same event produces no change)  

This guarantees:
> Even under concurrency, the system converges to the same correct state.

---

## Q9. What consistency model does your system follow?

**Strong Answer:**

The system follows **eventual consistency with strong local determinism**.

- Event ingestion is asynchronous  
- Events may arrive in any order  
- Temporary inconsistencies are possible  
- But the system guarantees that:
  - Given the same set of events  
  - The final state will always be the same  

So:
- Not strictly consistent in real-time  
- But **deterministically consistent over time**

---

## Q10. Why did you choose this architecture?

**Strong Answer:**

I chose this architecture because the primary constraint is **input unreliability, not system complexity**.

Key reasons:
- Webhooks are inherently unreliable (duplicates, delays, retries)  
- We need **idempotency at ingestion level**  
- We need **auditability for debugging courier disputes**  
- We need **fast reads for downstream systems**  

So separating:
- Event ingestion (write-heavy, immutable)  
- State computation (deterministic, overwrite-friendly)  

gives us:
- Scalability  
- Debuggability  
- Predictability under failure conditions  