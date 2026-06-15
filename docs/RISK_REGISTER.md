# Risk Register

**Date:** 2026-06-15
**Status:** Draft
**Related Document:** TECHNICAL_STRATEGY_MEMO.md

---

## Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-------------|---------|------------|
| R1 | `receivedAt` clock skew causes wrong event ordering | High | High | Use `receivedAt` as tiebreaker; implement a grace window for near-order events; log all ordering decisions in audit trail |
| R2 | Partner sends same `eventId` with a different payload on retry | Medium | Medium | Log and reject duplicates; assess whether payload hash or version tracking is needed in Phase 2 |
| R3 | Terminal state enforcement gaps - invalid transitions accepted | Medium | High | Explicit status transition rules; terminal states (`DELIVERED`, `RETURNED`) block all further updates |
| R4 | Write bottleneck at scale (SQLite single-writer) | Medium | Medium | Define a clear migration trigger (e.g., events-per-day threshold); document PostgreSQL migration path for Phase 2 |
| R5 | New status value needed from partner | Medium | Medium | Version the resolver interface; define a review process for adding new statuses |
| R6 | Out-of-order grace window too strict or too lenient | Medium | Medium | Start with a narrow window; adjust based on observed partner behaviour; log all borderline cases |
| R7 | Partner payload schema drift | Medium | Low | Validate at ingestion boundary; maintain schema versioning for normaliser |
| R8 | Second courier sends fundamentally different event shapes requiring partner-specific logic | Medium | High | Keep the resolver stateless and generic; defer partner-specific normalisation to Phase 2 |
| R9 | Retention cleanup deletes events still in dispute (e.g., legal hold) | Low | High | Terminal-state shipments are exempt from cleanup; consider per-shipment retention flags in Phase 2 |
| R10 | Batch events arrive so frequently out of order that state is frequently wrong | Medium | High | Implement grace window before Partner B onboarding; agree on expected event ordering with partner |
| R11 | Audit log grows unbounded for active shipments (no purge for terminal states) | Low | Medium | Monitor growth rate; consider snapshot-based compaction for long-running shipments in Phase 2 |

---

## Risk Assessment Notes

**Likelihood scale:** Low / Medium / High
**Impact scale:** Low / Medium / High

**R1 remains the highest-priority risk.** The shift to `receivedAt` as the ordering authority addresses some concerns, but clock skew between the two partners means cross-partner ordering is still unreliable. The grace window is the primary mitigation and should be implemented before Partner B onboarding.

**R3 is the highest-impact risk.** If terminal states are not enforced correctly, a late event could re-open a delivered or returned shipment, causing incorrect state across all downstream systems. The rules engine must treat `DELIVERED` and `RETURNED` as hard terminal states.

**R8 is the new highest-likelihood risk.** Partner B's "frequently out of order" and batch-based delivery suggests their event semantics may differ from Partner A. If their events require partner-specific resolution logic, the stateless generic resolver will be insufficient. This should be validated early.

**R10 is the new risk introduced by Partner B.** If Partner B frequently sends older events after newer ones, and we reject the older ones, state may end up wrong. The grace window approach (hold events briefly before applying) needs to be designed and agreed with the client.

---

## Closed Risks

None at this stage.
