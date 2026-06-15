package com.accso.shipment.dto;

import com.accso.shipment.domain.ShipmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response DTO for audit log API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {

    private String eventId;
    private String shipmentId;
    private String partner;
    private ShipmentStatus previousStatus;
    private ShipmentStatus newStatus;
    private String decision;
    private String rejectionReason;
    private Instant receivedAt;
    private Instant createdAt;
}
