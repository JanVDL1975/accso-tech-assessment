# Risk Register

**Date:** 2026-06-15
**Status:** Draft
**Related Document:** TECHNICAL_STRATEGY_MEMO.md

---

## Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-------------|---------|------------|
| R1 | `occurredAt` clock skew causes wrong event ordering | High | High | Use `receivedAt` as tiebreaker; implement a grace window for near-order events; log all ordering decisions in audit trail |
| R2 | Partner sends same `eventId` with a different payload on retry | Medium | Medium | Log and reject duplicates; assess whether payload hash or version tracking is needed in Phase 2 |
| R3 | Terminal state enforcement gaps - invalid transitions accepted | Medium | High | Explicit status transition rules; terminal states (`DELIVERED`, `RETURNED`) block all further updates |
| R4 | Write bottleneck at scale (SQLite single-writer) | Medium | Medium | Define a clear migration trigger (e.g., events-per-day threshold); document PostgreSQL migration path for Phase 2 |
| R5 | New status value needed from partner | Medium | Medium | Version the resolver interface; define a review process for adding new statuses |
| R6 | Out-of-order grace window too strict or too lenient | Medium | Medium | Start with a narrow window; adjust based on observed partner behaviour; log all borderline cases |
| R7 | Partner payload schema drift | Medium | Low | Validate at ingestion boundary; maintain schema versioning for normaliser |

---

## Risk Assessment Notes

**Likelihood scale:** Low / Medium / High
**Impact scale:** Low / Medium / High

**R1 is the highest-priority risk.** Since `occurredAt` is partner-supplied and cannot be trusted as a global clock, the architecture must not depend on it being monotonically increasing. The combination of `occurredAt` ordering with `receivedAt` as a tiebreaker, plus a grace window for near-order events, is the primary mitigation. All ordering decisions must be audit-logged so the reasoning can be reviewed.

**R3 is the highest-impact risk.** If terminal states are not enforced correctly, a late event could re-open a delivered or returned shipment, causing incorrect state across all downstream systems. The rules engine must treat `DELIVERED` and `RETURNED` as hard terminal states.

---

## Closed Risks

None at this stage.