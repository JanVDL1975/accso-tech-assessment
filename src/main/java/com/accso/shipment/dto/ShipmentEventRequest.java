package com.accso.shipment.dto;

import com.accso.shipment.domain.ShipmentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Request DTO for shipment event ingestion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentEventRequest {

    @NotBlank(message = "eventId is required")
    private String eventId;

    @NotBlank(message = "partner is required")
    private String partner;

    @NotBlank(message = "shipmentId is required")
    private String shipmentId;

    @NotNull(message = "status is required")
    private ShipmentStatus status;

    @NotNull(message = "occurredAt is required")
    private Instant occurredAt;

    @NotNull(message = "receivedAt is required")
    private Instant receivedAt;

    private String location;
}
