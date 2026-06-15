package com.accso.shipment.controller;

import com.accso.shipment.dto.BatchEventResponse;
import com.accso.shipment.dto.EventIngestionResponse;
import com.accso.shipment.dto.ShipmentEventRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for receiving shipment events from courier partners.
 * A single endpoint accepts both a single event and a batch of events.
 * The format is auto-detected based on the JSON structure:
 * - Single object: {"eventId": "...", "partner": "...", ...}
 * - Array: [{"eventId": "...", ...}, {...}]
 */
@RestController
@RequestMapping("/api/v1/shipments/events")
@RequiredArgsConstructor
public class ShipmentEventController {

    private final com.accso.shipment.service.ShipmentEventService eventService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<?> receiveEvent(@RequestBody JsonNode body) throws Exception {
        if (body.isArray()) {
            List<ShipmentEventRequest> events = objectMapper.readValue(
                    objectMapper.treeAsTokens(body),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ShipmentEventRequest.class));
            BatchEventResponse response = eventService.receiveBatchEvents(events);
            return ResponseEntity.ok(response);
        } else {
            ShipmentEventRequest request = objectMapper.treeToValue(body, ShipmentEventRequest.class);
            EventIngestionResponse response = eventService.receiveEvent(request);
            return ResponseEntity.status(HttpStatus.OK).body(response);
        }
    }
}
