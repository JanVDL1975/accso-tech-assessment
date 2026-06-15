package com.accso.shipment.dto;

import com.accso.shipment.domain.ShipmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response DTO for shipment event history.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentEventResponse {

    private String eventId;
    private String shipmentId;
    private String partner;
    private ShipmentStatus status;
    private Instant occurredAt;
    private Instant receivedAt;
    private String location;
    private Boolean accepted;
    private String rejectionReason;
}