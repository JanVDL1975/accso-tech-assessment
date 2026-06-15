package com.accso.shipment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for batch event ingestion.
 * Partners that send events in bulk use this endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchEventRequest {

    @NotEmpty
    @Valid
    private List<ShipmentEventRequest> events;
}
