package com.accso.shipment.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShipmentStatusTest {

    @Test
    void isValidTransition_labelCreated_toHandedToCarrier() {
        assertTrue(ShipmentStatus.isValidTransition(ShipmentStatus.LABEL_CREATED, ShipmentStatus.HANDED_TO_CARRIER));
    }

    @Test
    void isValidTransition_handedToCarrier_toInTransit() {
        assertTrue(ShipmentStatus.isValidTransition(ShipmentStatus.HANDED_TO_CARRIER, ShipmentStatus.IN_TRANSIT));
    }

    @Test
    void isValidTransition_inTransit_toOutForDelivery() {
        assertTrue(ShipmentStatus.isValidTransition(ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY));
    }

    @Test
    void isValidTransition_inTransit_toDeliveryException() {
        assertTrue(ShipmentStatus.isValidTransition(ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERY_EXCEPTION));
    }

    @Test
    void isValidTransition_outForDelivery_toDelivered() {
        assertTrue(ShipmentStatus.isValidTransition(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERED));
    }

    @Test
    void isValidTransition_outForDelivery_toDeliveryException() {
        assertTrue(ShipmentStatus.isValidTransition(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERY_EXCEPTION));
    }

    @Test
    void isValidTransition_deliveryException_toInTransit() {
        assertTrue(ShipmentStatus.isValidTransition(ShipmentStatus.DELIVERY_EXCEPTION, ShipmentStatus.IN_TRANSIT));
    }

    @Test
    void isValidTransition_deliveryException_toReturned() {
        assertTrue(ShipmentStatus.isValidTransition(ShipmentStatus.DELIVERY_EXCEPTION, ShipmentStatus.RETURNED));
    }

    @Test
    void isValidTransition_null_from_acceptsAnyStatus() {
        assertTrue(ShipmentStatus.isValidTransition(null, ShipmentStatus.LABEL_CREATED));
        assertTrue(ShipmentStatus.isValidTransition(null, ShipmentStatus.IN_TRANSIT));
    }

    @Test
    void isValidTransition_invalidTransition_returnsFalse() {
        assertFalse(ShipmentStatus.isValidTransition(ShipmentStatus.LABEL_CREATED, ShipmentStatus.DELIVERED));
        assertFalse(ShipmentStatus.isValidTransition(ShipmentStatus.DELIVERED, ShipmentStatus.IN_TRANSIT));
    }

    @Test
    void isValidTransition_sameStatus_returnsFalse() {
        assertFalse(ShipmentStatus.isValidTransition(ShipmentStatus.IN_TRANSIT, ShipmentStatus.IN_TRANSIT));
    }
}
