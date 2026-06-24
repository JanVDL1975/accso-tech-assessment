# REVISED_INTERVIEW_FLASHCARDS.md

---

# 1. System Design Flashcards

## Flashcard 1
**Q:** What problem does the Shipment Integrity Service solve?  
**A:** It ensures a trustworthy shipment state despite duplicate, delayed, out-of-order, or conflicting courier events.

---

## Flashcard 2
**Q:** What is the core system goal?  
**A:** Maintain a single reliable shipment state even when upstream courier data is inconsistent.

---

## Flashcard 3
**Q:** What are the main processing stages?  
**A:** Ingestion → Deduplication → Resolution → Persistence → Query.

---

## Flashcard 4
**Q:** How are duplicate events handled?  
**A:** Using `(partner, eventId)` deduplication.

---

## Flashcard 5
**Q:** Why is `receivedAt` used instead of `occurredAt`?  
**A:** Because `occurredAt` is unreliable across partners.

---

## Flashcard 6
**Q:** What is stored in the system?  
**A:** Raw events, derived events, and audit logs.

---

## Flashcard 7
**Q:** Why store raw events?  
**A:** For auditability, replay, and compliance.

---

## Flashcard 8
**Q:** Why store derived events?  
**A:** To represent canonical business transitions efficiently.

---

## Flashcard 9
**Q:** Why store audit logs?  
**A:** To explain why decisions were made.

---

# 2. Architecture Decisions

## Flashcard 10
**Q:** Why use a dual-store model?  
**A:** It balances O(1) reads with replay capability.

---

## Flashcard 11
**Q:** What is the trade-off of dual-store?  
**A:** Potential divergence between raw and derived state.

---

## Flashcard 12
**Q:** Why is append-only storage used?  
**A:** To preserve history and enable replay.

---

## Flashcard 13
**Q:** Why use a resolver interface?  
**A:** To support partner-specific logic in the future.

---

# 3. Event Sourcing & Streaming

## Flashcard 14
**Q:** Why not full Event Sourcing?  
**A:** Because current-state reads dominate; event sourcing would make reads O(n).

---

## Flashcard 15
**Q:** What is the chosen model instead of event sourcing?  
**A:** A hybrid model with raw events + derived state.

---

## Flashcard 16
**Q:** Why not Kafka?  
**A:** Single consumer system; Kafka adds unnecessary complexity.

---

## Flashcard 17
**Q:** When would Kafka become appropriate?  
**A:** Multiple downstream consumers or independent pipelines.

---

# 4. Invariants & Correctness

## Flashcard 18
**Q:** What are the system invariants?  
**A:** Deduplication, ordering, auditability, valid state transitions.

---

## Flashcard 19
**Q:** How is ordering enforced?  
**A:** Using `receivedAt`.

---

## Flashcard 20
**Q:** How are state transitions validated?  
**A:** Through a permissive state machine validating observed transitions.

---

## Flashcard 21
**Q:** Is the system strict or permissive?  
**A:** Permissive; it validates observed transitions, not full workflows.

---

# 5. Bug & Failure Analysis

## Flashcard 22
**Q:** What was the receivedAt bug?  
**A:** Implementation still used `occurredAt` despite ADR specifying `receivedAt`.

---

## Flashcard 23
**Q:** Why was the bug missed?  
**A:** Tests used identical timestamps, hiding ordering issues.

---

## Flashcard 24
**Q:** How do you prevent this bug?  
**A:** Divergent timestamp tests + ADR-to-code validation.

---

## Flashcard 25
**Q:** What is the biggest production risk?  
**A:** Silent state drift due to lack of observability.

---

## Flashcard 26
**Q:** What other production risks exist?  
**A:** SQLite write bottleneck, no correction mechanism, unknown partner ordering behavior.

---

# 6. Scaling & Migration

## Flashcard 27
**Q:** When do you migrate from SQLite?  
**A:** When write contention, latency, or scale exceeds single-writer limits.

---

## Flashcard 28
**Q:** What are the migration signals?  
**A:** Write pressure, lock contention, growth forecasts.

---

## Flashcard 29
**Q:** Why is SQLite used initially?  
**A:** Simplicity and sufficiency for early-stage workloads.

---

## Flashcard 30
**Q:** When would PostgreSQL be needed?  
**A:** When ingestion throughput or concurrency exceeds SQLite limits.

---

# 7. Corrections & Data Integrity

## Flashcard 31
**Q:** How are corrections handled?  
**A:** Through correction events, not direct updates.

---

## Flashcard 32
**Q:** Why not update state directly?  
**A:** It breaks auditability and append-only guarantees.

---

## Flashcard 33
**Q:** What does correction preserve?  
**A:** History, audit trail, and system integrity.

---

# 8. Design Trade-offs

## Flashcard 34
**Q:** Why split raw, derived, and audit data?  
**A:** Separation of ingestion, business logic, and decision traceability.

---

## Flashcard 35
**Q:** What is the trade-off of this separation?  
**A:** More complexity, but much better clarity and compliance.

---

## Flashcard 36
**Q:** Why is this system not fully event-driven?  
**A:** Because it optimises for current-state queries, not stream processing.

---

# 9. Communication Patterns

## Flashcard 37
**Q:** Architecture answer structure?  
**A:** Decision → Reason → Trade-off → Recommendation.

---

## Flashcard 38
**Q:** Bug answer structure?  
**A:** What happened → Why missed → Prevention.

---

## Flashcard 39
**Q:** Production answer structure?  
**A:** Current behaviour → Risk → Mitigation.

---

## Flashcard 40
**Q:** Leadership answer structure?  
**A:** Acknowledge → Trade-off → Recommendation.

---

# 10. Common Interview Questions

## Flashcard 41
**Q:** Walk me through the system.  
**A:** Ingestion → deduplication → ordering via receivedAt → derived state → APIs.

---

## Flashcard 42
**Q:** Why dual-store?  
**A:** O(1) reads + replay capability.

---

## Flashcard 43
**Q:** Why not compute state on query?  
**A:** Would be O(n) and too slow for production use.

---

## Flashcard 44
**Q:** What would you do with one more week?  
**A:** Observability, ADR validation, correction API, better tests.

---

## Flashcard 45
**Q:** What is the key system risk?  
**A:** Silent state drift due to missing observability.

---

# 11. Failure Pattern Fixes

## Flashcard 46
**Q:** Instead of “SQLite serializes writes”, say?  
**A:** Evaluate write contention, latency, and scale before migration.

---

## Flashcard 47
**Q:** Instead of “add metrics”, say?  
**A:** Identify risk (state drift), then mitigate with observability.

---

## Flashcard 48
**Q:** Instead of “first event must be label created”?  
**A:** The system enforces invariants, not rigid workflows.

---

# END OF FLASHCARDS