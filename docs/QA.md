# Questions & Answers

## For the Client

---

### 1. What is the existing tech stack?

---

### 2. How is the occurredAt timestamp generated and how do we ensure time consistency across systems?

---

### 3. What is the purpose of the receivedAt timestamp - is it system ingestion time, and how is it used (e.g. ordering, auditing, reconciliation)?

---

### 4. Is the eventId a numeric counter or a UUID? UUIDs are great for distributed uniqueness in webhook systems, but random UUIDs can hurt index locality in write-heavy databases, so I'd consider sequential or time-ordered IDs depending on the workload.

---

### 5. Do status updates originate directly from delivery drivers, or are they aggregated via hubs/partner systems before being sent to us?

---

### 6. What is the expected frequency and burst rate of shipment status updates (e.g. peak events per second or per shipment)?

---

### 7. Where is eventId generated - at the hub, courier system, or upstream provider - and should we treat it as globally unique?

---

### 8. Can you clarify the hosting model for the ingestion layer - are we assuming a serverless architecture (e.g. Lambda/API Gateway) or container-based deployment on cloud infrastructure?

---

### 9. Since different courier providers generate their own identifiers, do we expect variability in formats for fields like eventId and shipmentId, and should we treat them as opaque external IDs?

---

### 10. Or are we standardising them via a shared ingestion contract?

---

### 11. Do we persist provider-specific event formats, or do we transform incoming events into a canonical model at ingestion time? If so, should that mapping happen at the ingestion layer or downstream in the processing pipeline?
