package com.accso.shipment.controller;

import com.accso.shipment.dto.ShipmentStatusResponse;
import com.accso.shipment.service.ShipmentEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Controller for querying shipment status.
 */
@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
public class ShipmentStatusController {

    private final ShipmentEventService eventService;

    @GetMapping("/{shipmentId}/status")
    public ResponseEntity<ShipmentStatusResponse> getStatus(@PathVariable String shipmentId) {
        Optional<ShipmentStatusResponse> status = eventService.getShipmentStatus(shipmentId);
        return status.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
