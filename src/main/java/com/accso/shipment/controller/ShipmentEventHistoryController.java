package com.accso.shipment.controller;

import com.accso.shipment.dto.ShipmentEventResponse;
import com.accso.shipment.service.ShipmentEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for querying shipment event history.
 */
@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
public class ShipmentEventHistoryController {

    private final ShipmentEventService eventService;

    /**
     * Get full event history for a shipment, ordered by receivedAt ascending.
     */
    @GetMapping("/{shipmentId}/events")
    public ResponseEntity<List<ShipmentEventResponse>> getEventHistory(@PathVariable String shipmentId) {
        List<ShipmentEventResponse> history = eventService.getEventHistory(shipmentId);
        return ResponseEntity.ok(history);
    }
}