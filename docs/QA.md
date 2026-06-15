# Questions & Answers

## For the Client

---

### 1. What is the existing tech stack?

**Answer:** No house stack or preferred infra. Yours to decide and justify - the reasoning for those calls is more interesting than the calls themselves.

---

### 2. How is the occurredAt timestamp generated and how do we ensure time consistency across systems?

**Answer:** `occurredAt` is the courier's own claim of when the event happened, generated upstream in their systems - not by us. You cannot trust it as a clean global clock: expect clock skew between couriers, varying precision, and occasional backfilled timestamps. Design for it rather than around it.

---

### 3. What is the purpose of the receivedAt timestamp - is it system ingestion time, and how is it used (e.g. ordering, auditing, reconciliation)?

**Answer:** `receivedAt` is partner-supplied. It represents the ingestion time into the partner's system - when they first found out about the event.

---

### 4. Is the eventId a numeric counter or a UUID? UUIDs are great for distributed uniqueness in webhook systems, but random UUIDs can hurt index locality in write-heavy databases, so I'd consider sequential or time-ordered IDs depending on the workload.

**Answer:** Treat `eventId` as an opaque string owned by the partner. Formats will vary between couriers - do not assume a specific structure or that it is globally unique across partners.

---

### 5. Do status updates originate directly from delivery drivers, or are they aggregated via hubs/partner systems before being sent to us?

**Answer:** Events arrive from the courier partner's system via webhook, already aggregated on their side - not pushed straight from a driver's handheld. The partner is your integration and trust boundary.

---

### 6. What is the expected frequency and burst rate of shipment status updates (e.g. peak events per second or per shipment)?

**Answer:** A sensible order-of-magnitude assumption is plenty - no specific figures required.

---

### 7. Where is eventId generated - at the hub, courier system, or upstream provider - and should we treat it as globally unique?

**Answer:** `eventId` is generated at the hub, courier system, or upstream provider. It is not globally unique across partners - treat it as opaque and partner-scoped.

---

### 8. Can you clarify the hosting model for the ingestion layer - are we assuming a serverless architecture (e.g. Lambda/API Gateway) or container-based deployment on cloud infrastructure?

**Answer:** Yours to decide and justify. There is no preferred hosting model - pick what lets you build a credible thin slice and defend the choice.

---

### 9. Since different courier providers generate their own identifiers, do we expect variability in formats for fields like eventId and shipmentId, and should we treat them as opaque external IDs?

**Answer:** Yes. Treat `eventId` and `shipmentId` as opaque strings owned by the respective partner. Formats will vary between couriers.

---

### 10. Or are we standardising them via a shared ingestion contract?

**Answer:** Yours to decide and justify. No prescribed ingestion contract - architect as you see fit.

---

### 11. Do we persist provider-specific event formats, or do we transform incoming events into a canonical model at ingestion time? If so, should that mapping happen at the ingestion layer or downstream in the processing pipeline?

**Answer:** Yours to decide. No requirement to persist raw provider formats - architect the canonical model approach and justify the decision.