package com.accso.shipment.controller;

import com.accso.shipment.dto.AuditLogResponse;
import com.accso.shipment.service.ShipmentEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for querying audit log entries.
 */
@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
public class ShipmentAuditController {

    private final ShipmentEventService eventService;

    /**
     * Get audit log for a shipment, ordered by createdAt ascending.
     * Shows the full resolution decision history — why state changed or didn't change.
     */
    @GetMapping("/{shipmentId}/audit")
    public ResponseEntity<List<AuditLogResponse>> getAuditLog(@PathVariable String shipmentId) {
        List<AuditLogResponse> auditLog = eventService.getAuditLog(shipmentId);
        return ResponseEntity.ok(auditLog);
    }
}
