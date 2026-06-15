package com.accso.shipment.service;

import com.accso.shipment.entity.ShipmentCurrentStateEntity;
import com.accso.shipment.entity.ShipmentEventEntity;

/**
 * Pluggable interface for custom state resolution logic.
 */
public interface ShipmentStateResolver {

    ShipmentResolutionResult resolve(ShipmentEventEntity incoming, ShipmentCurrentStateEntity current);
}
