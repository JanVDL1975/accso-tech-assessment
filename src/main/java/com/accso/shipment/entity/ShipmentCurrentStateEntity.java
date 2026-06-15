package com.accso.shipment.entity;

import com.accso.shipment.domain.ShipmentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Derived view of current shipment state.
 */
@Entity
@Table(name = "shipment_current_state")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentCurrentStateEntity {

    @Id
    @Column(name = "shipment_id")
    private String shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false)
    private ShipmentStatus currentStatus;

    @Column(name = "last_occurred_at", nullable = false)
    private Instant lastOccurredAt;

    @Column(name = "last_received_at", nullable = false)
    private Instant lastReceivedAt;

    private String location;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
