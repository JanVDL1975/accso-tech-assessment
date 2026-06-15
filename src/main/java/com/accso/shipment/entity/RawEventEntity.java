package com.accso.shipment.entity;

import com.accso.shipment.domain.ShipmentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Raw event store — every incoming event persisted as-received, before any processing.
 * 30-day retention. Unique constraint on (eventId, partner) for deduplication.
 */
@Entity
@Table(name = "raw_events",
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "partner"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawEventEntity {

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
    @Column(nullable = false)
    private ShipmentStatus status;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    private String location;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
