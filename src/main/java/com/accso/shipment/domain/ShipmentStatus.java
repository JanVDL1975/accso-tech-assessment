package com.accso.shipment.domain;

import java.util.Map;
import java.util.Set;

/**
 * Shipment status enum with allowed transitions.
 */
public enum ShipmentStatus {
    LABEL_CREATED,
    HANDED_TO_CARRIER,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    DELIVERY_EXCEPTION,
    RETURNED;

    private static final Map<ShipmentStatus, Set<ShipmentStatus>> ALLOWED_TRANSITIONS = Map.of(
        LABEL_CREATED, Set.of(HANDED_TO_CARRIER),
        HANDED_TO_CARRIER, Set.of(IN_TRANSIT),
        IN_TRANSIT, Set.of(OUT_FOR_DELIVERY, DELIVERY_EXCEPTION),
        OUT_FOR_DELIVERY, Set.of(DELIVERED, DELIVERY_EXCEPTION),
        DELIVERY_EXCEPTION, Set.of(IN_TRANSIT, RETURNED)
    );

    public boolean canTransitionTo(ShipmentStatus target) {
        Set<ShipmentStatus> allowed = ALLOWED_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }

    public static boolean isValidTransition(ShipmentStatus from, ShipmentStatus to) {
        if (from == null) {
            return true; // No current state, any starting state is valid
        }
        return from.canTransitionTo(to);
    }
}
