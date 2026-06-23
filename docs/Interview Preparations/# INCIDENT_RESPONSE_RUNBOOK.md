# INCIDENT_RESPONSE_RUNBOOK.md
## Shipment Integrity Service — SEV-1 / SEV-2 Playbooks

This runbook defines operational response procedures for the Shipment Integrity Service, a webhook-driven system responsible for maintaining authoritative shipment state across unreliable courier events.

---

# 🚨 INCIDENT SEVERITY MODEL

## 🔴 SEV-1 (Critical — Customer Impact + Data Corruption Risk)

**Definition:**
- Incorrect shipment state OR inability to trust system outputs
- Silent data corruption or divergence from real-world state

**Examples:**
- Derived state mismatch detected
- Transition failure spike
- Out-of-order explosion causing incorrect state
- Audit log failure
- Idempotency conflicts indicating data integrity breach

---

## 🟠 SEV-2 (High — Degraded System Behaviour)

**Definition:**
- System still correct but degraded performance or partial instability

**Examples:**
- High ingestion latency
- Increased backlog / queue depth
- Elevated DB latency
- Increased duplicate rates
- Partner-specific event issues

---

# 🔴 SEV-1 PLAYBOOK (CRITICAL INCIDENTS)

---

## 1. Detection & Confirmation

### Trigger Sources
- Metrics alert (Prometheus/Grafana)
- Reconciliation mismatch detection
- Customer escalation (incorrect shipment state reported)

### Immediate Validation (5–10 min)
- Is issue **system-wide or partner-specific?**
- Is state **incorrect or just delayed?**

Check key metrics:
- transition_failure_rate
- derived_state_mismatch
- out_of_order_rate
- audit_log_failure_rate

---

## 2. Immediate Actions (STOP THE BLEEDING)

### 🔒 Step 1: Freeze state updates
- Stop updates to `current_state`
- Switch to **read-only derived mode**

---

### 🔁 Step 2: Trigger replay mode
- Rebuild state from `raw_events`
- Recompute shipment state using `ShipmentStateResolver`

---

### 🚫 Step 3: Quarantine bad input
- Identify offending partner(s)
- Apply:
  - ingestion pause OR
  - strict validation mode

---

### 🧯 Step 4: Rollback risky deployments
- Rollback resolver or ingestion logic if recently deployed

---

## 3. Isolation Strategy

- Single partner → isolate ingestion pipeline
- Multi-partner → rollback resolver globally
- System-wide mismatch → full freeze + replay

---

## 4. Data Integrity Protection

- Never delete raw events
- Never overwrite audit logs
- Preserve append-only guarantees

---

## 5. Recovery Procedure

### Step 1: Rebuild state
- Replay `raw_events` ordered by `receivedAt`
- Apply correct resolver logic

### Step 2: Validate correctness
- Ensure derived state == recomputed state

### Step 3: Gradual restore
- Re-enable ingestion per partner
- Monitor correctness metrics first

---

## 6. Post-Incident Actions

- Write RCA
- Add regression tests:
  - timestamp divergence cases
- Update ADRs if needed
- Add missing monitoring signals

---

# 🟠 SEV-2 PLAYBOOK (DEGRADED PERFORMANCE)

---

## 1. Detection

### Triggers:
- Elevated latency
- Growing backlog
- DB saturation

---

## 2. Immediate Actions

### 📈 Scale ingestion layer
- Increase instances / pods

---

### 📉 Reduce non-critical work
- Defer:
  - audit enrichment
  - secondary logging
- Prioritise:
  1. state resolution
  2. deduplication
  3. persistence

---

### 🧵 Apply backpressure
- Rate limit per partner if required
- Enable buffering

---

### 💾 DB optimisation
- Check lock contention
- Increase connection pool
- Batch writes if needed

---

## 3. Monitoring Focus

- ingestion rate stability
- DB write latency
- queue backlog trend
- end-to-end latency

---

## 4. Escalation (SEV-2 → SEV-1)

Escalate immediately if:
- state correctness is impacted
- derived state mismatch appears
- ordering drift spikes
- audit failures occur

---

## 5. Recovery

- Scale down once backlog stabilises
- Validate correctness unaffected during incident

---

# 🧠 INCIDENT RESPONSE PRINCIPLES

---

## 🔴 Correctness > Everything
If correctness breaks → freeze + replay

---

## 🟡 Latency issues are survivable
Degrade gracefully + scale

---

## 📈 Throughput issues require buffering
Throttle + absorb load

---

## 🟢 Integrity issues require stopping processing
Fail closed to preserve trust

---

# 🎯 KEY DECISION MATRIX

| Signal | Action |
|--------|--------|
| State mismatch | Freeze + replay |
| Transition failure spike | Rollback resolver |
| Out-of-order spike | Quarantine partner |
| Latency spike | Scale + degrade |
| Backlog growth | Throttle + buffer |
| DB contention | Reduce write pressure |
| Audit failure | Stop processing |

---