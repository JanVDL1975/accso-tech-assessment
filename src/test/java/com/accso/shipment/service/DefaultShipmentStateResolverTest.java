package com.accso.shipment.service;

import com.accso.shipment.domain.ShipmentStatus;
import com.accso.shipment.entity.ShipmentCurrentStateEntity;
import com.accso.shipment.entity.ShipmentEventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DefaultShipmentStateResolverTest {

    private DefaultShipmentStateResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DefaultShipmentStateResolver();
    }

    @Test
    void resolve_noCurrentState_acceptsEvent() {
        ShipmentEventEntity incoming = createEvent("ship-1", ShipmentStatus.IN_TRANSIT, "2026-03-10T10:00:00Z");

        ShipmentResolutionResult result = resolver.resolve(incoming, null);

        assertTrue(result.isAccepted());
        assertEquals(ShipmentStatus.IN_TRANSIT, result.getNewStatus());
    }

    @Test
    void resolve_newerEvent_updatesState() {
        ShipmentCurrentStateEntity current = createCurrentState("ship-1", ShipmentStatus.IN_TRANSIT, "2026-03-10T10:00:00Z");
        ShipmentEventEntity incoming = createEvent("ship-1", ShipmentStatus.OUT_FOR_DELIVERY, "2026-03-10T12:00:00Z");

        ShipmentResolutionResult result = resolver.resolve(incoming, current);

        assertTrue(result.isAccepted());
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, result.getNewStatus());
    }

    @Test
    void resolve_olderEvent_doesNotUpdate() {
        ShipmentCurrentStateEntity current = createCurrentState("ship-1", ShipmentStatus.OUT_FOR_DELIVERY, "2026-03-10T12:00:00Z");
        ShipmentEventEntity incoming = createEvent("ship-1", ShipmentStatus.IN_TRANSIT, "2026-03-10T10:00:00Z");

        ShipmentResolutionResult result = resolver.resolve(incoming, current);

        assertTrue(result.isAccepted());
        assertNull(result.getNewStatus()); // No state update
    }

    @Test
    void resolve_invalidTransition_rejects() {
        ShipmentCurrentStateEntity current = createCurrentState("ship-1", ShipmentStatus.DELIVERED, "2026-03-10T12:00:00Z");
        ShipmentEventEntity incoming = createEvent("ship-1", ShipmentStatus.IN_TRANSIT, "2026-03-10T13:00:00Z");

        ShipmentResolutionResult result = resolver.resolve(incoming, current);

        assertFalse(result.isAccepted());
        assertEquals("INVALID_TRANSITION", result.getRejectionReason());
    }

    @Test
    void resolve_deliveryExceptionRecovery_toInTransit() {
        ShipmentCurrentStateEntity current = createCurrentState("ship-1", ShipmentStatus.DELIVERY_EXCEPTION, "2026-03-10T10:00:00Z");
        ShipmentEventEntity incoming = createEvent("ship-1", ShipmentStatus.IN_TRANSIT, "2026-03-10T12:00:00Z");

        ShipmentResolutionResult result = resolver.resolve(incoming, current);

        assertTrue(result.isAccepted());
        assertEquals(ShipmentStatus.IN_TRANSIT, result.getNewStatus());
    }

    @Test
    void resolve_deliveryExceptionRecovery_toReturned() {
        ShipmentCurrentStateEntity current = createCurrentState("ship-1", ShipmentStatus.DELIVERY_EXCEPTION, "2026-03-10T10:00:00Z");
        ShipmentEventEntity incoming = createEvent("ship-1", ShipmentStatus.RETURNED, "2026-03-10T12:00:00Z");

        ShipmentResolutionResult result = resolver.resolve(incoming, current);

        assertTrue(result.isAccepted());
        assertEquals(ShipmentStatus.RETURNED, result.getNewStatus());
    }

    private ShipmentEventEntity createEvent(String shipmentId, ShipmentStatus status, String receivedAt) {
        Instant ts = Instant.parse(receivedAt);
        return ShipmentEventEntity.builder()
                .eventId("evt-" + System.nanoTime())
                .shipmentId(shipmentId)
                .partner("dhl")
                .status(status)
                .occurredAt(ts)
                .receivedAt(ts)
                .location("Amsterdam")
                .build();
    }

    private ShipmentCurrentStateEntity createCurrentState(String shipmentId, ShipmentStatus status, String lastReceivedAt) {
        Instant receivedAt = Instant.parse(lastReceivedAt);
        return ShipmentCurrentStateEntity.builder()
                .shipmentId(shipmentId)
                .currentStatus(status)
                .lastOccurredAt(receivedAt)  // occurredAt matches receivedAt in test data
                .lastReceivedAt(receivedAt)
                .location("Amsterdam")
                .updatedAt(Instant.now())
                .build();
    }
}
