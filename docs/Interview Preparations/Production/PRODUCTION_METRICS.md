# Shipment Integrity Service — Production Metrics & Observability Model

This document defines the key production metrics for the Shipment Integrity Service, grouped into four observability pillars.

| Pillar | Focus |
|--------|-------|
| 🔴 Correctness | Silent data corruption risk |
| 🟡 Latency | Customer perception |
| 📈 Throughput | Capacity planning |
| 🟢 Integrity | Trust in system |

---

## 🔴 Correctness Metrics (SEV-1 — Silent Data Corruption Risk)

These metrics detect incorrect system behaviour, even when the system appears healthy.

| Metric | Unit | SLO | Warning | Page |
|--------|------|-----|---------|------|
| Out-of-order rate | % | < 2% | > 5% | > 10% sustained |
| Transition failure rate | % of total transitions | < 0.5% | > 1% | > 2% |
| Derived state consistency drift | mismatches / 1,000 shipments | 0 | any detected anomaly | any confirmed mismatch |
| Ordering drift severity | ms / min | p95 < 10 min drift | p95 > 10 min | p99 > 30 min or sudden spike |
| Event reprocessing rate | % | < 5% | > 10% | > 20% |
| Idempotency conflict rate | % | ~0% | > 0.1% | any sustained occurrence |

---

## 🟡 Latency Metrics (Customer Perception)

These metrics define how fresh and responsive the system feels.

| Metric | Unit | SLO | Warning | Page |
|--------|------|-----|---------|------|
| End-to-end latency (ingestion → state visible) | ms | p95 < 1s | > 2s | > 5s sustained |
| Ingestion latency | ms | p95 < 200ms | > 300ms | > 500ms |
| State resolution latency | ms | p95 < 300ms | > 500ms | > 1s |
| Audit lag | ms / s | < 1s | > 2s | > 5s |
| System freshness lag | s / min | < 60s | > 2 min | > 5 min |

---

## 📈 Throughput Metrics (Capacity Planning)

These metrics ensure the system can handle load and scale.

| Metric | Unit | SLO | Warning | Page |
|--------|------|-----|---------|------|
| Ingestion rate | events/sec (per partner + global) | within baseline ±20% | ±30% | > 50% spike/drop |
| DB write throughput | writes/sec | within capacity envelope | 80% capacity | 95% sustained |
| Queue / backlog depth (if async) | events / time lag | near zero steady state | backlog > 10 min | continuously growing |
| Duplicate event rate | % | stable baseline (partner-dependent) | 2x baseline deviation | 5x spike or collapse to zero |
| Payload validation failure rate | % | < 1% | > 3% | > 5% |

---

## 🟢 Integrity Metrics (Trust in System)

These metrics ensure the system is auditable, consistent, and reliable.

| Metric | Unit | SLO | Warning | Page |
|--------|------|-----|---------|------|
| Audit log write success rate | % | 100% | < 99.9% | any failure |
| Deduplication hit ratio | % | stable baseline | significant deviation | sudden collapse or spike |
| DB write latency | ms (p95 / p99) | < 100ms | > 150ms | > 300ms |
| DB error rate | % | < 0.1% | > 0.5% | > 1% |
| Lock contention rate | lock waits/sec or % | near zero | sustained increase | DB stalls or prolonged waits |
| Idempotency conflict rate | % | ~0% | — | any sustained occurrence |
| Derived state replay consistency | mismatches in reconciliation runs | 0 | any anomaly | confirmed mismatch |

---

## Summary

| Pillar | Purpose |
|--------|---------|
| 🔴 Correctness | Shipment state is logically valid |
| 🟡 Latency | Real-time freshness for users |
| 📈 Throughput | Scalability under load |
| 🟢 Integrity | Trust in stored and derived data |

---

## Definitions

| Term | Definition |
|------|------------|
| **p95 / p99** | 95th / 99th percentile. p95 < 1s means 95% of requests complete within 1 second. Used instead of average to capture tail behaviour — a single slow request can skew the mean without reflecting what most users experience. |
| **SLO (Service Level Objective)** | The target the team commits to meeting. Breach triggers internal review. Looser than SLA. |
| **SLA (Service Level Agreement)** | The contractual obligation to customers. Typically slightly looser than SLO and may carry financial penalties if violated. |
| **Warning threshold** | An alert requiring attention but not yet a fire. Indicates degraded performance approaching the SLO. |
| **Page threshold** | An alert that wakes someone up. Indicates the SLO is at risk and requires immediate investigation. |
| **"sustained"** | The condition persists over a defined window (e.g., 5 minutes), not a single momentary spike. Prevents paging on transient blips. |
| **Derived state consistency drift** | A mismatch between the state computed by replaying events and the stored current state. Indicates the current state is stale or incorrect. |
| **Ordering drift** | How far the authoritative timestamp (`receivedAt`) of events can be out of true chronological order. High drift means events are arriving significantly later than their actual sequence. |
| **Idempotency conflict** | Two distinct event submissions that the system cannot distinguish as duplicates, despite having different event IDs. Indicates the deduplication key may be insufficient. |
| **Lock contention** | Multiple threads or connections competing for the same database lock, causing writes to queue and latency to spike. |
| **SEV-1** | Severity 1 — the highest priority alert category. Used here for correctness metrics where silent corruption is the risk: the system appears healthy while returning wrong answers. |
