# Interview Discussion Points

| # | Section |
|---|---------|
| 1 | [Your Assumptions and Where the Brief Was Underspecified](#1-your-assumptions-and-where-the-brief-was-underspecified) |
| 2 | [What You Would Clarify with the Client Before Committing a Team](#2-what-you-would-clarify-with-the-client-before-committing-a-team) |
| 3 | [Why You Structured the Delivery Plan the Way You Did](#3-why-you-structured-the-delivery-plan-the-way-you-did) |
| 4 | [How Your Architecture and Plan Changed After the Mandatory Change Request](#4-how-your-architecture-and-plan-changed-after-the-mandatory-change-request) |
| 5 | [What You Would Postpone Even If the Client Pushed for More Scope](#5-what-you-would-postpone-even-if-the-client-pushed-for-more-scope) |
| 6 | [What Kind of Architecture Is This?](#6-what-kind-of-architecture-is-this) |
| 7 | [How Would You Scale This?](#7-how-would-you-scale-this) |
| 8 | [Security](#8-security) |
| 9 | [Availability](#9-availability) |
| 10 | [Reliability](#10-reliability) |
| 11 | [Performance](#11-performance) |
| 12 | [Cost](#12-cost) |
| 13 | [Maintainability](#13-maintainability) |
| 14 | [Where the Architecture Is Most Likely to Fail Under Real Organizational Constraints](#14-where-the-architecture-is-most-likely-to-fail-under-real-organizational-constraints) |
| 15 | [What AI Accelerated and What Still Required Your Judgment](#15-what-ai-accelerated-and-what-still-required-your-judgment) |
| 16 | [Bug #5 — How It Slipped Through and What It Reveals](#16-bug-5--how-it-slipped-through-and-what-it-reveals) |
| 17 | [How You Would Present the Strategy to a Non-Technical Client Stakeholder](#17-how-you-would-present-the-strategy-to-a-non-technical-client-stakeholder) |

---

**Section Summaries**

**1 — Your Assumptions:** Deduplication key is `(partner, eventId)`; timestamp authority is `receivedAt` for Partner B but `occurredAt` was original default; retention was not in original scope; Partner B batching was a change request item.

**2 — What to Clarify:** Deduplication edge case (same ID, different payload); grace window duration; terminal state correction workflow; SQLite migration threshold; Partner B onboarding scope; retention legal edge cases.

**3 — Delivery Plan Structure:** Phase 1 centralises authoritative logic for one partner; core loop (dedup + ordering + resolution) is tested together; flat Phase 1 avoids early interface definition; Phase 2 deliberately underspecified until Partner B data arrives.

**4 — Change Request Impact:** Single endpoint replaced dual endpoints; three event stores replaced one (raw, derived, audit); `@EnableScheduling` re-added; ordering corrected from `occurredAt` to `receivedAt` in ADR but not yet in code.

**5 — What to Postpone:** Multi-partner normalisation until real Partner B data; grace window until calibrated; metrics dashboard until correctness is proven; full CI/CD pipeline; snapshot compaction.

**6 — Architecture Type:** API-centric synchronous event processing, not Kafka-style streaming. Append-only store enables audit and recomputation but queries hit derived current state, not replay.

**7 — Scaling:** SQLite single-writer is first bottleneck → PostgreSQL migration; read path scales horizontally; partition by partner if volumes diverge; Kafka only when multi-consumer streaming is needed.

**8 — Security:** Webhook authentication undefined; partner-specific payload validation deferred; partner isolation by query convention not DB enforcement; audit log integrity relies on access control, not cryptography.

**9 — Availability:** SQLite single instance = single point of failure; no HA, no failover; events lost during downtime; partner retry window is only guarantee.

**10 — Reliability:** Crash between event write and state update leaves stale state; no automatic repair mechanism; duplicate retries handled by deduplication; event loss if partner retry window closes.

**11 — Performance:** Reads fast with indexes; writes serialised (SQLite) ~500–1000/s; O(1) for deduplication and state updates; history queries need index on `receivedAt`.

**12 — Cost:** Phase 1 runs on a single t3.medium; no managed services; PostgreSQL migration adds ~$15/month; `derived_events` storage grows indefinitely without retention policy.

**13 — Maintainability:** Flat package structure fine for thin slice; audit trail is primary debug tool; no metrics or alerting in Phase 1; Hibernate schema management not production-grade; resolver pluggability exists but no partner-specific implementations yet.

**14 — Failure Points:** Terminal state corrections have no first-class mechanism; Partner B out-of-order rate unknown; SQLite write bottleneck before migration; retention cleanup can contend with live traffic; pluggable resolver needs partner implementations not yet built.

**15 — AI vs Human:** AI accelerated diagrams, documentation structure, and well-specified code. AI did not accelerate: requirements extraction, timestamp authority trade-off, spotting output mismatches, judging what not to build.

**16 — The Bug:** ADR-001 updated to `receivedAt` but resolver code still used `occurredAt`; tests set both timestamps identically so bug was invisible; fix: update resolver, add regression test with divergent timestamps.

**17 — Stakeholder Framing:** Store everything, use only what matters; system knows when not to trust an event; phased delivery validates approach before full build; legal retention explained clearly.

---

## 1. Your Assumptions and Where the Brief Was Underspecified

The assignment brief described the problem clearly but left several operational details unconstrained. I made the following assumptions:

**Deduplication scope.** The brief said `eventId` identifies events but did not specify cross-partner uniqueness. I assumed `eventId` is partner-scoped — the same ID can appear in multiple partners' systems — so deduplication must be keyed on `(partner, eventId)`. The client confirmed this in Q&A.

**Timestamp reliability.** The brief mentioned `occurredAt` and `receivedAt` but did not specify which to use for ordering. Phase 1 was scoped to a single partner only — Partner B was not in scope at all. For Partner A's known behaviour, `occurredAt` was a reasonable default. The decision to use `receivedAt` as the ordering authority came only when the change request introduced Partner B, whose out-of-order delivery made `occurredAt` actively unreliable — more on that in the change request section.

**Retention requirements.** The brief said nothing about data retention. The client later imposed legal requirements: raw payloads deleted after 30 days, audit decisions retained for 1 year. This was not in the original scope.

**Batch ingestion.** The brief described a webhook receiving "events" without specifying whether Partner B sends one at a time or in batches. Partner B's batch-based delivery was a change request item, not the original brief.

**Partner B specifics.** The brief mentioned a second courier "within one quarter" but gave no details on their event format, delivery frequency, or out-of-order rate. I treated Partner B generically in Phase 1, deferring partner-specific normalisation.

**What I would not assume without clarification:** grace window duration, whether terminal-state shipments ever need manual correction, whether the thin slice needs a production-ready hosting model, and the exact volume of events per day that would trigger a SQLite migration.

---

## 2. What You Would Clarify with the Client Before Committing a Team

Before scaling to a team, I would want the following questions resolved:

**Deduplication edge cases.** If a partner retries with the same `eventId` but a corrected payload (different status), the current system silently rejects the second attempt. Is that the desired behaviour? Or should we detect payload changes and flag them for review? This affects whether we need a payload hash in the deduplication key.

**Grace window.** If Partner B sends events 5 minutes apart out of order, do we want to hold the later event and wait, or apply it immediately? A grace window adds latency to state updates but prevents wrong state. The cost and duration need client sign-off.

**Terminal state corrections.** When a shipment is `DELIVERED` and the client later discovers it was delivered to the wrong address, what is the correction workflow? Is a manual database update acceptable, or do they need a first-class "corrected" event type?

**Scale trigger.** At what event volume does SQLite become a constraint? There is no explicit scale target in the brief. I would agree an explicit threshold (e.g., events-per-day) that triggers a PostgreSQL migration conversation rather than letting it drift until performance degrades.

**Partner B onboarding scope.** The change request asks whether "onboarded" means generic batch ingestion or full partner-specific integration. These have very different effort profiles. I would clarify how much partner-specific logic is expected in Phase 2.

**Legal retention edge cases.** The 30-day raw / 1-year audit windows are confirmed as legal requirements, but edge cases remain: what happens if a shipment is in dispute when the cleanup job runs? The current exemption for terminal-state shipments is my interpretation, not a confirmed answer.

---

## 3. Why You Structured the Delivery Plan the Way You Did

The core problem was that the same webhook was being consumed by multiple downstream teams, each applying their own deduplication, ordering, and conflict resolution logic — and getting different answers. There was no single broken system to replace. The problem was fragmented and decentralised.

The delivery plan's Phase 1 was about **centralising** the authoritative logic in one place. Once it works, downstream teams stop their own implementations and point to the new service. Phase 2 is about expanding to Partner B and making it production-ready.

**Phase 1 was scoped to single partner.** Partner B was not in scope at all. A thin slice for Partner A demonstrates the centralising logic correctly before adding another partner's complexity.

**The core loop is the risky part.** Deduplication, out-of-order handling, conflict resolution, and terminal state enforcement are where the business logic lives. These are interdependent — you cannot test conflict resolution without deduplication working first. Building them together as one slice means you get a testable, demonstrable system that proves the centralisation works.

**A flat Phase 1 avoids integration surprises.** If I had split ingestion from resolution into separate phases, I would have had to define their interface before knowing what each side actually needed. Building the full stack in one pass in Phase 1 let the interface be discovered through actual use.

**Batch ingestion and retention entered Phase 1 via the change request.** Partner B's introduction — and the legal retention requirements — were not in the original scope. Both arrived via change request and were absorbed into the current delivery rather than deferred to Phase 2.

**Phase 2 is deliberately underspecified.** The change request opened workstreams (Partner B normalisation, grace window, metrics, hosting) but the delivery plan does not commit to a sequence or effort estimate. I would sequence those based on Partner B's actual onboarding date, not in advance.

---

## 4. How Your Architecture and Plan Changed After the Mandatory Change Request

The mandatory change request added three things: batch ingestion, legal retention, and Partner B preparation. The impact on the architecture was:

**Single endpoint, auto-detected.** The original design had separate endpoints for single and batch events. The change request collapsed them into one. This simplified the API surface and avoided divergence in processing logic between the two paths. The controller now inspects the request payload at runtime.

**Three event stores instead of one.** The original architecture had an append-only event store. The change request introduced `raw_events` (30-day retention), `derived_events` (canonical events, indefinite), and `audit_log` (1-year retention). This is architecturally cleaner — raw payloads are preserved for legal compliance, canonical events drive state, and audit decisions are independently queryable.

**Scheduling re-enabled.** `@EnableScheduling` had been removed. It was re-added to support the daily retention cleanup jobs. This was a one-line change but represents a class of risk — accidental removal of infrastructure annotations.

**Ordering authority corrected for Partner B.** Phase 1 was scoped to Partner A only, where `occurredAt` was a reasonable default. Partner B's out-of-order delivery made `occurredAt` actively unreliable. ADR-001 was updated to specify `receivedAt` as authoritative — anticipating Partner B's needs. However, the resolver code was not updated at the same time. This is the confirmed bug.

**Phase 2 scope defined.** The change request opened workstreams: Partner B normalisation, grace window, metrics, and hosting. These were not in the original Phase 1 scope at all — Partner B was a change request item, not a planned expansion.

The architecture did not change in its fundamental structure — the three-layer model (ingestion, resolution, query) remained intact. The change was additive: new stores, new jobs, and a corrected ordering decision anticipating Partner B's arrival.

---

## 5. What You Would Postpone Even If the Client Pushed for More Scope

**Multi-partner normalisation in Phase 2.** Partner B was not in the original Phase 1 scope — it arrived via change request. I would resist any pressure to handle partner-specific normalisation before we have real Partner B event data. The generic resolver is the right approach for Phase 1. Partner-specific mapping comes after we have observed Partner B's actual event shapes and behaviour.

**Grace window before calibration.** Similarly, a grace window sounds straightforward but its correct duration depends on observed out-of-order frequency. Setting it to 5 minutes without data could be too strict or too lenient. I would start logging borderline cases and set the window after a week of Partner B traffic.

**Metrics and alerting dashboard.** The operational dashboard is genuinely useful but it does not affect correctness. A team without observability will learn the hard way; a team without correct business logic will give wrong answers to customers. Ship correctness first.

**CI/CD beyond test execution.** Basic `mvn test` on every commit is necessary but the full pipeline (docker builds, environment promotions, deployment approvals) is Phase 2. The thin slice is not yet deployable to production in any case — it needs a hosting decision first.

**Snapshot compaction.** The audit log can grow unbounded for long-running active shipments. This is a real risk but it is low likelihood in the near term. Monitoring the growth rate and designing compaction when needed beats premature optimisation.

---

## 6. What Kind of Architecture Is This?

This is not an event-driven or streaming architecture in the Kafka sense. The distinction matters:

- **Event-driven (streaming):** Events flow through a broker, consumers react asynchronously, state is derived by consuming the event stream. The event log is the source of truth and queries replay it.

- **This system:** A synchronous webhook receives an event, processes it immediately, and stores derived state. The event store is append-only and serves as an audit trail — but queries hit `shipment_current_state`, not by replaying events. The API is request-driven, not message-based.

The accurate description is **API-centric state derivation** or **synchronous event processing**: events trigger immediate processing that derives and stores current state. The append-only event store enables audit and recomputation, but it is not the primary query path.

**Why not Kafka?**

Kafka would be a reasonable choice for a production multi-consumer system at scale. It was not chosen because:

1. **The brief didn't ask for it.** The problem was a webhook receiving events and producing a queryable current state. Kafka would have solved a problem not in scope.

2. **Scope control.** Kafka adds significant infrastructure: cluster management, topic configuration, consumer groups, offset management, dead letter queues, consumer lag monitoring, partitioning strategy. All of that would have distracted from the core business logic. The thin slice would have been infrastructure-heavy.

3. **Single consumer.** Kafka's strength is multiple independent consumers processing the same event stream differently. Here there is one authoritative service consuming one webhook — no need for a distributed log.

4. **Lightweight intent.** SQLite was chosen deliberately — zero configuration, easy to run, appropriate for a demonstration. Kafka requires a completely different operational model.

Kafka makes sense when: multiple downstream systems consume events in different ways, genuine async processing with replay is required, or volume demands partitioning across consumers. None of those were Phase 1 concerns.

---

## 7. How Would You Scale This?

Three scaling concerns in order of likely arrival:

**1. SQLite single-writer bottleneck (first trigger)**

SQLite serialises writes. With one service instance this is fine; with multiple instances or high volume, write throughput plateaus. The documented migration path is PostgreSQL. That migration is non-trivial — schema mapping, data cutover, connection pool retuning — but the architecture isolates persistence so it is a targeted change, not a rewrite. The trigger threshold should be agreed with the client upfront rather than discovered under pressure.

**2. Horizontal scaling (read path is easy)**

The service is stateless — state lives in the database, not the service. Read queries (`/status`, `/events`) can scale by adding service instances behind a load balancer with read replicas of PostgreSQL. Write path is harder because all instances write to the same database, but read replicas offload the query load that typically dominates.

**3. Partner-specific processing at scale**

If Partner B's event volume is significantly higher than Partner A's, the natural partition key is `(partner, eventId)` — the same deduplication key. Partitioning by partner would isolate their traffic and prevent one partner's volume from affecting the other's throughput.

**The graceful progression:**

```
Phase 1      → SQLite, single instance, demonstrable
Phase 1.5    → PostgreSQL migration, read replicas
Phase 2      → Horizontal scaling, multiple instances
Phase 2+     → Partner partitioning if volumes diverge
Phase 3+     → Kafka if multi-consumer streaming becomes needed
```

**What I'd resist:** Premature Kafka adoption. It solves a scaling problem this system does not have yet. Add it when there are multiple independent consumers consuming the event stream in different ways — not before.

---

## 8. Security

The webhook is publicly exposed — authentication is the immediate production concern. The current design trusts the partner boundary but does not document how partners authenticate. In production: HMAC signatures (partner signs the payload with a shared secret), API keys, or mutual TLS. Without that, anyone can submit events.

Input validation is partially handled — the service rejects malformed JSON, but partner-specific payload validation is deferred. A malicious or buggy partner could send payloads that bypass the ingestion layer in unexpected ways.

Partner isolation: `raw_events` and `derived_events` are partitioned by `partner` only in the query path — not enforced at the database level. A bug in a query could accidentally cross-contaminate partner data. In production, row-level security or separate schemas per partner would be appropriate.

Audit log integrity: the audit trail is append-only in intent, but nothing prevents an admin with database access from updating or deleting records. If audit logs need legal evidentiary weight, write-once storage or cryptographic chaining would be needed.

---

## 9. Availability

The SQLite deployment is a single point of failure. No HA, no replicas, no automatic failover. If the service or database instance goes down, events are lost — partners retry but there's no guarantee they won't drop events during downtime.

What happens to events sent during downtime? Currently undefined. A proper answer: partners retry with the same `eventId`, deduplication catches the retry, but only if the downtime window is shorter than the retry window. If the partner drops events after N retries, some events are genuinely lost.

The retention cleanup jobs run daily — if they take too long or lock tables, they could contend with normal event processing. In production, these should run during low-traffic windows with timeout guards.

---

## 10. Reliability

Reliability means the system gives correct answers consistently, not just that it stays up.

If the service crashes mid-processing — after writing to `raw_events` but before updating `current_state` — the event is persisted but the state is not updated. On restart, the event is in the store but the shipment shows the wrong state until another event arrives and triggers a recomputation. There is no automatic recovery mechanism.

The append-only event store is the resilience primitive — replay from it would produce correct state (with the `receivedAt` bug fixed). But there is no scheduled replay job to detect and correct state drift after a crash.

Duplicate submission: Partners retry. Deduplication is per `(eventId, partner)` — if the same event is retried with a different `eventId`, it would be treated as a new event. Whether this is correct depends on whether `eventId` is stable across retries. If not, a payload hash in the deduplication key would be needed.

**The failure hierarchy:**

```
Service down           → events lost during downtime (partner retry helps if window is short)
Database corruption    → event store is append-only, recoverable from backup
State drift            → no automatic repair, depends on next event triggering replay
Duplicate events       → handled correctly by deduplication (stable eventId assumed)
Event loss             → partner retry window is the only guarantee; undefined in Phase 1
```

---

## 11. Performance

SQLite performance is predictable for this workload:

- **Reads:** fast with indexes on `shipmentId`. Status queries and event history are single-row or indexed-range lookups. No joins, no aggregation over large datasets.

- **Writes:** single-writer only — one transaction at a time, serialised. Under moderate load this is fine; under high volume from multiple partners it will bottleneck. Max throughput roughly 500–1000 writes per second on a modest instance — enough for a single courier's event volume, not enough for multiple high-volume partners.

- **Deduplication check:** indexed lookup on `(eventId, partner)` — O(1), no performance concern.

- **State derivation:** single-row upsert — O(1), no performance concern.

- **Event history query:** orders by `receivedAt`, needs an index. With long-running shipments (hundreds of events), history queries are still fast as long as the index exists.

What hurts performance:
- High write throughput from multiple partners simultaneously (SQLite single-writer)
- Very long event histories per shipment (history query traverses more rows)
- Retention cleanup running during peak traffic hours

---

## 12. Cost

Phase 1 is cheap to run:

- **Single service instance:** t3.medium on cloud ($20–30/month on AWS)
- **SQLite on local disk:** no separate storage cost beyond the instance disk
- **No message queue, no separate cache, no managed database:** minimal operational overhead

When to PostgreSQL:
- Managed PostgreSQL (db.t3.micro for dev, ~$15/month for minimal production) adds cost but gains replication, backups, and HA
- `derived_events` storage grows with event volume — no automated purge in Phase 1, cost grows indefinitely without a retention policy

**Partner B cost impact:** Partner B's batch model could increase event volume significantly. Batch ingestion is processed event-by-event, so write throughput pressure is the same as single events — just arriving in bursts. Storage grows faster with larger or more frequent batches.

---

## 13. Maintainability

**Code organisation:** The codebase is flat — controllers, services, entities, repositories, DTOs in package-by-layer groups. For a thin slice this is fine. As the system grows, a package-by-feature structure would be more maintainable: group all partner-related code together, all resolution logic together, all retention logic together.

**Debugging:** The audit trail is the primary debugging tool — every decision is logged with reasoning. But not all code paths write useful audit entries. The most important path (resolver accepting or rejecting) is logged; the ingestion path less so. In production, structured logging (JSON, with `shipmentId` and `eventId` as fields) would make log aggregation and searching significantly easier than the current text logs.

**Operational monitoring:** No metrics, no alerting, no dashboards in Phase 1. When something goes wrong in production, there is no observable signal — you are reacting to customer complaints, not detecting the problem first.

**Schema evolution:** `derived_events` and `audit_log` have no automated schema migration. Adding a new column requires a migration. With append-only stores, adding columns is safer than modifying existing ones, but Hibernate's schema management is not production-grade. Flyway or Liquibase would be appropriate before production.

**Testing gaps:** Unit and integration tests exist for resolver and controller paths. Missing: contract tests (API schema stability), performance tests (baseline throughput), chaos tests (database slowness), and regression tests for `receivedAt` ordering (the bug that slipped through).

**Partner extensibility:** Adding Partner B's normalisation requires a new normaliser class implementing the `Normaliser` interface. Clean in principle. In practice, partner-specific implementations don't exist yet — the generic resolver handles Partner B events today, which works but may not handle their specific semantics optimally.

**The maintenance burden over time:**
- Retention cleanup grows more expensive as tables grow — needs index maintenance and partition management
- `derived_events` has no automated purge — storage grows indefinitely for active shipments
- No alerting means reliability incidents surface through downstream complaints, not proactive detection
- Resolver pluggability is architecturally clean but partner-specific implementations are Phase 2 work

---

## 14. Where the Architecture Is Most Likely to Fail Under Real Organizational Constraints

**Terminal state corrections with manual intervention.** In production, someone will ring the client and say "this shipment was delivered to the wrong address." The current system has no first-class correction mechanism — a manual database update or a special "corrected" event type would be needed. Without a defined process, engineers will make ad hoc fixes that bypass the audit trail.

**Partner B out-of-order rate.** This is a Phase 2 risk, not a Phase 1 design flaw. The architecture handles out-of-order events correctly for Partner A's profile. If Partner B's out-of-order rate is high, a grace window needs to be designed and calibrated — without it, state will be wrong for a meaningful fraction of their shipments. This is the highest operational risk to address before Partner B goes live.

**SQLite single-writer bottleneck.** The architecture document flags this explicitly. SQLite handles concurrent reads fine but serialises writes. Under load from multiple courier partners, write throughput will plateau. The migration path to PostgreSQL is documented but not trivial — it requires a migration strategy, schema mapping, and a cutover plan. If volume arrives faster than the migration, the system will back up.

**Retention cleanup on live data.** The cleanup jobs delete old records based on `receivedAt`. If a shipment's raw events are deleted but a dispute arises after deletion, the client loses the raw evidence. The terminal-state exemption mitigates this for closed cases, but it is a partial answer. A legal hold mechanism (per-shipment retention flags) would be the proper solution but is Phase 2.

**Resolver pluggability at scale.** The `ShipmentStateResolver` interface is architecturally clean but the default resolver is a single class with a fixed ruleset. If Partner B needs different rules from Partner A, the pluggable interface gives you the hook but you still have to write and test the partner-specific logic. The interface exists but the partner-specific implementations do not yet.

**Deduplication: code and database together.** Deduplication uses both a code check and a database unique constraint:

- **Code check:** `rawEventRepository.existsByEventIdAndPartner(eventId, partner)` — returns a structured `DUPLICATE` response and logs the detection before returning
- **Database constraint:** `UniqueConstraint(columnNames = {"event_id", "partner"})` on `raw_events` — enforces the constraint at the database level as a hard backstop

The division of responsibility: code handles the API contract — the structured response and logging that the caller sees. The database provides the integrity guarantee — nothing slips through even if the code check has a bug. `derived_events` has no uniqueness constraint; it relies on `raw_events` as the deduplication gate, and only accepts events that passed through that gate.

**State machine: permissive, not strict.** Two design decisions in the resolver:

*First event is not required to be LABEL_CREATED.* When there is no current state, any incoming status is accepted as valid. If the first event received is `IN_TRANSIT`, it is accepted. This is deliberate — courier partners do not always send the label creation event, and enforcing `LABEL_CREATED` as mandatory first would block valid shipments from being tracked.

*Intermediate states are not required.* A shipment can skip intermediate states as long as the path is valid. `LABEL_CREATED → IN_TRANSIT` is permitted even if `HANDED_TO_CARRIER` was never sent. The system does not infer missing states — it simply accepts that the current state is whatever the most recent valid event says it is.

Strict enforcement (every intermediate state must appear in order) would detect gaps but would reject valid shipments that genuinely skip steps. The design chose permissive forward progression over strict completeness.

---

## 15. What AI Accelerated and What Still Required Your Judgment

**AI accelerated: documentation structure and diagrams.** The SDLC flowchart, the architecture diagrams, and the overall documentation layout were produced by AI. This was genuinely faster than drawing it by hand. The mental model of separating README (navigation), docs (design reasoning), and src-level context was useful scaffolding.

**AI accelerated: first-draft code for well-specified logic.** The core resolver logic, entity definitions, and repository interfaces are patterns AI handles competently when the rules are explicit. Given the status transition table and deduplication rules, producing the first pass at `DefaultShipmentStateResolver` and `ShipmentEventService` was faster than writing it from scratch.

**AI did not accelerate: requirements extraction.** The brief was a natural language document. Turning it into `REQUIREMENTS.md` required reading every line carefully, deciding what was a hard requirement and what was guidance, and making explicit assumptions. AI can draft this but cannot know which ambiguities are safe to resolve and which need client confirmation.

**AI did not accelerate: the ordering authority decision.** Three options were on the table:

1. **`occurredAt`** — the event's actual timestamp. Rejected because it is backfilled by partners, especially Partner B. When Partner B accumulates events over hours and sends them as a batch, `occurredAt` values can be hours earlier than `receivedAt`, making ordering completely wrong for entire batch windows.

2. **Our own ingestion timestamp** — wall-clock time when our system receives the event. Rejected because it severs the link to the partner's own timeline. If Partner B sends batch 1 at 09:00 and batch 2 at 17:00, but batch 2 arrives at our system before batch 1 due to network timing, our own clock would order them backwards — introducing our own artifacts into the partner's ordering logic.

3. **`receivedAt`** — when the partner's own system received the event. Chosen because it is the partner's ingestion timestamp, closer to event time than our receipt, and preserves the partner's chronological sequence without our network artifacts. Not perfect — Partner B's clock can still drift — but far more stable than `occurredAt` for the backfill problem.

This evaluation was a domain reasoning task, not pattern matching. I evaluated the trade-offs and made the call.

**AI did not accelerate: spotting where its own output was wrong.** AI proposed using separate endpoints for single and batch events. I changed it to a single endpoint after the change request. AI did not flag that decision — the requirement change forced it. More broadly, AI produced plausible documentation that did not always match the actual codebase structure. Human review was required to catch the mismatch.

**AI did not accelerate: judging what not to build.** When AI suggested adding a "Key Concepts" section to the README, I judged it would add speculative content beyond what the brief required. That judgment — what to leave out — is not something AI applies well without explicit criteria. The CLAUDE.md principle of minimum code applies to documentation too.

The pattern: AI is effective for well-specified, deterministic tasks with clear inputs and outputs. It struggles with ambiguity, trade-off reasoning, and knowing when the problem definition has changed.

---

## 16. Bug #5 — How It Slipped Through and What It Reveals

**What happened.** Phase 1 was scoped to Partner A only — Partner B was never part of the original plan. Using `occurredAt` was a reasonable default for Partner A's known behaviour. The client's Q&A raised a flag about `occurredAt` being unreliable, but that was a theoretical warning at that stage — Partner B didn't exist yet. When the change request introduced Partner B with frequent out-of-order events, the `occurredAt` assumption became actively wrong rather than just theoretically risky. ADR-001 was updated to specify `receivedAt` as authoritative, anticipating Partner B's needs. But the resolver code was not updated to match — it still operated as if it were serving only Partner A. The architecture doc also still showed the old decision. This is the gap: the ADR was updated to reflect the new problem, but the implementation was still running the old Partner A logic.

---

### How did this slip through if you had tests?

The tests set both timestamps to the same value in the test data, so they passed regardless of which timestamp the code actually checked. The test covered the happy path (newer event updates state, older event does not) but did not distinguish between `occurredAt` and `receivedAt` ordering. The tests were written to match the bug, not to verify correct behaviour independently.

A test with divergent timestamps — `occurredAt` earlier than `receivedAt`, or vice versa — would have caught this immediately. No such test existed.

---

### What's to stop the same thing happening again?

The immediate fix is a regression test that uses divergent timestamps. But the deeper answer is process: the gap between ADR and code should have been flagged in review. What I would introduce:

**Code-to-ADR traceability in review.** When reviewing a PR that touches resolver logic, the reviewer should explicitly check whether any ADRs apply and whether the code matches them. Not as a bureaucratic step — as a practiced habit.

**AI-assisted ADR checking.** The prompt that generates resolver code should include the relevant ADRs as context. If the prompt says "implement out-of-order detection per ADR-001", the output is more likely to be correct than if it just says "implement out-of-order detection."

**Divergent test data as a pattern.** The existing tests set both timestamps identically because that was the simplest path. A lint rule or code review checklist that flags same-value timestamps in resolver tests would catch this class of bug earlier.

---

### Did you add a regression test?

Not yet — the fix was committed with the current test suite passing. Adding the regression test is the right next step. A proper test would look like:

```java
@Test
void resolve_outOfOrderByReceivedAt_notByOccurredAt() {
    // Current state: IN_TRANSIT, receivedAt = 12:00, occurredAt = 11:00
    // (partner received it at 12:00 but event actually happened at 11:00)
    ShipmentCurrentStateEntity current = createCurrentState(
        "ship-1",
        ShipmentStatus.IN_TRANSIT,
        "2026-03-10T12:00:00Z",   // lastReceivedAt
        "2026-03-10T11:00:00Z"    // lastOccurredAt
    );

    // Incoming: OUT_FOR_DELIVERY, receivedAt = 11:30, occurredAt = 12:30
    // (received earlier than current's receivedAt → should NOT update)
    ShipmentEventEntity incoming = createEvent(
        "ship-1",
        ShipmentStatus.OUT_FOR_DELIVERY,
        "2026-03-10T11:30:00Z",   // receivedAt — earlier than current
        "2026-03-10T12:30:00Z"    // occurredAt — later than current
    );

    ShipmentResolutionResult result = resolver.resolve(incoming, current);

    // receivedAt is older → no update
    assertTrue(result.isAccepted());
    assertNull(result.getNewStatus());
}
```

This verifies that `receivedAt` is the deciding signal, not `occurredAt`.

---

### You had ADR-001 correct but the code didn't match. What process should have caught that?

Code review is the obvious answer, but the more honest one is that the ADR was updated after the code was written, and no one went back to check the existing code against the updated ADR. A PR that touches an ADR should require a check that all code implementing that ADR is also updated — or a note in the ADR saying which code implements it. Without that link, a doc change can drift silently.

In a team context, I'd also want ADRs to have an "implemented by" field linking to the relevant code. That way a doc change triggers a review of the linked code, and a code review checks whether the linked ADR is up to date.

---

### Did the AI generate the resolver code? Was the prompt accurate?

Yes, AI generated the initial resolver. The prompt described the rules correctly — out-of-order detection, transition validation, terminal states — but did not specify which timestamp to use. It followed the brief at face value and used `occurredAt`. The prompt was accurate as far as it went, but it did not encode the Q&A context about timestamp reliability. That context existed in the QA document, not in the prompt sent to AI.

---

### The test data sets both timestamps to the same value. Does the test actually verify the ordering logic?

No, not for this specific bug. The existing tests verify that the comparison logic works — older events don't update, newer events do — but they don't distinguish which timestamp is being compared. With both timestamps identical in the test data, the code under test could use either one and the tests would pass. That is exactly what happened.

---

### What other places might have the same ADR/code mismatch?

The most likely candidates are places where documentation was updated but code wasn't, or where the brief was followed literally without cross-referencing the Q&A. The ordering decision is the confirmed instance. The other ADRs — deduplication key, batch isolation, split retention — should be checked against their implementations. A systematic check would be: for each ADR, find the code that implements it and verify they match.

---

### The ShipmentEventService writes both timestamps. Is it storing them correctly?

Yes. The service writes both `receivedAt` and `occurredAt` from the incoming request to the entity. The resolver reads `getLastReceivedAt()` from the current state and `getReceivedAt()` from the incoming event. Both are stored and passed correctly — the bug was purely in which one the resolver compared, not in the storage path.

---

### If this had shipped and caused wrong state for Partner B, how would you have detected it and recovered?

Detection: the audit log records every resolution decision, including the comparison made. If we had `receivedAt` logging in the audit trail (which we do — the decision includes the timestamps), an analyst could replay the events for a given shipment and see whether the ordering was correct. In production, I'd want a metric: count of events rejected as out-of-order per partner per day. An unexpectedly high rate for Partner B would flag this.

Recovery: the event store is append-only. For any shipment where state is wrong, we can replay the events in correct `receivedAt` order and recompute the state. The resolution logic is deterministic and stateless — same inputs give same outputs. So the fix is: correct the resolver, then run a replay job for affected shipments. No data is lost because every event is preserved.

---

### What would you have done if the client found this before you did?

I would have accepted the finding without defensiveness, reproduced it to confirm it was real, and escalated the fix immediately. The architecture and ADRs would have been updated first to document the correct design, then the code fix would follow, then a regression test. I would also have done a broader sweep of the codebase for similar inconsistencies before notifying the client that the fix was complete.

Finding it myself — as happened here — is the better outcome because I controlled the framing. Finding it in front of a client or interviewer is uncomfortable. Finding it in production is worse. This is why the process should catch it before shipping.

---

## 17. How You Would Present the Strategy to a Non-Technical Client Stakeholder

I would frame it around three business outcomes, avoiding technical jargon:

**We store everything but only use what matters.** Every event from every courier is recorded — even duplicates and events that arrive late. This means if there is ever a dispute, we have the evidence. But the current state of a shipment is derived from the most recent reliable event only, so customers and support staff always see an accurate, consistent answer.

**The system knows when not to trust an event.** When a courier sends an event that contradicts what we already know — an older status arriving after a newer one — the system recognises this, records it, but does not let it change the current state. This prevents the kind of confusion where a shipment appears to revert to an earlier stage. Terminal states (delivered, returned) are final — once reached, nothing can change them without human action.

**We are not building everything at once.** Phase 1 gives you a working system for the first courier with all the core rules in place. Phase 2 adds the second courier, better handling of their out-of-order events, and production-ready operations. This way you can validate the approach with the first partner before committing to a larger build.

On the change request specifically: the legal retention requirements were not in the original scope. The system now keeps raw courier data for 30 days (as required by law) and keeps the record of every decision the system made for one year (also required). Cleanup runs automatically every night. Closed shipments are exempt from cleanup so disputes can still be investigated.

I would offer to walk through the status transition diagram on a whiteboard — it is the clearest way to show that the system cannot accidentally transition a delivered shipment backwards, which is the failure mode clients most fear.
