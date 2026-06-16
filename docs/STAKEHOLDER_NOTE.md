# Stakeholder Note: Key Trade-offs

This section summarises the non-obvious decisions in the Shipment Integrity Service for a client sponsor or engineering manager who needs to understand the reasoning without deep technical involvement.

**Data storage vs. query simplicity**  
The system stores every event it receives, even duplicates and out-of-order events. This makes audit trails complete and debugging straightforward, but means storage grows continuously. The practical implication is that data retention policies need to be defined early — without them, the database will grow indefinitely. There is a pending task to formalise a retention policy.

**State correctness vs. event timeliness**  
When a late or backfilled event arrives — for example, a courier system that was offline for three days finally reporting a delivery — the event is stored for the record but does not update the current shipment state. This is intentional: the system prioritises the authoritative "current truth" over historical completeness. In practice this means a shipment's state is always consistent with the most recent reliable event, but very old events that arrive late are preserved without effect. For most business flows this is the right behaviour, but if your process requires catching every historical update you would need to add a separate reconciliation step.

**Terminal states and manual correction**  
Once a shipment reaches DELIVERED or RETURNED, no further events can change it. This prevents a shipment from accidentally reverting to an earlier state — a common source of data corruption in less controlled systems. The trade-off is that genuine corrections (a delivery was actually to the wrong address, a return was initiated after delivery) require manual intervention rather than being absorbed automatically. For most shipments this is fine; for high-error-rate couriers it may need attention.

**SQLite simplicity vs. horizontal scale**  
The service uses SQLite, which is zero-configuration, easy to operate, and reliable. It is the right choice for a service that runs on a single node. If traffic grows significantly — or if you need read replicas across data centres — SQLite becomes a constraint and a different database would be needed. The architecture isolates persistence logic so that swap would be a targeted change, not a rewrite, but it is not trivial. If scale is a near-term concern, raise it now.

**AI-assisted development: speed vs. oversight**  
AI tools (Claude Code and ChatGPT) were used during this project to accelerate scaffolding, documentation, and design discussion. This was genuinely productive — particularly for structuring the documentation and exploring trade-offs in conversation. The important caveat is that AI output requires human verification: it produces plausible-sounding but not always correct suggestions. In this project, AI suggestions on documentation structure were reviewed against the actual codebase and adjusted where they didn't fit. Treat AI as a skilled colleague who sometimes over-engineers or assumes a context that doesn't match yours — the human judgment to override it remains essential.
