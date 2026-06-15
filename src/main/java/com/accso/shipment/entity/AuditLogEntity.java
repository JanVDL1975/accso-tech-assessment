package com.accso.shipment.entity;

import com.accso.shipment.domain.ShipmentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Immutable audit log — records every resolution decision for a shipment event.
 * Explains why state changed or didn't change. 1-year retention.
 */
@Entity
@Table(name = "audit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "shipment_id", nullable = false)
    private String shipmentId;

    @Column(nullable = false)
    private String partner;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status")
    private ShipmentStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status")
    private ShipmentStatus newStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditDecision decision;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public enum AuditDecision {
        ACCEPTED,
        REJECTED,
        NO_UPDATE
    }
}
