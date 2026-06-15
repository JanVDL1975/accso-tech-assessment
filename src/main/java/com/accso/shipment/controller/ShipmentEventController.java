package com.accso.shipment.controller;

import com.accso.shipment.dto.BatchEventRequest;
import com.accso.shipment.dto.BatchEventResponse;
import com.accso.shipment.dto.EventIngestionResponse;
import com.accso.shipment.dto.ShipmentEventRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for receiving shipment events from courier partners.
 */
@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
public class ShipmentEventController {

    private final com.accso.shipment.service.ShipmentEventService eventService;

    @PostMapping("/events")
    public ResponseEntity<EventIngestionResponse> receiveEvent(
            @Valid @RequestBody ShipmentEventRequest request) {
        EventIngestionResponse response = eventService.receiveEvent(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PostMapping("/events/list")
    public ResponseEntity<BatchEventResponse> receiveBatchEvents(
            @Valid @RequestBody BatchEventRequest request) {
        BatchEventResponse response = eventService.receiveBatchEvents(request);
        return ResponseEntity.ok(response);
    }
}
