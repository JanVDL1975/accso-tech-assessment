package com.accso.shipment.dto;

import com.accso.shipment.domain.ShipmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response DTO for shipment status query.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentStatusResponse {

    private String shipmentId;
    private ShipmentStatus currentStatus;
    private Instant lastReceivedAt;
    private String location;
}
