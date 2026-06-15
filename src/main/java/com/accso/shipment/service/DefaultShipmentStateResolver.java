package com.accso.shipment.service;

import com.accso.shipment.domain.ShipmentStatus;
import com.accso.shipment.entity.ShipmentCurrentStateEntity;
import com.accso.shipment.entity.ShipmentEventEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Default implementation of ShipmentStateResolver.
 * Handles idempotency, ordering checks, and transition validation.
 */
@Component
public class DefaultShipmentStateResolver implements ShipmentStateResolver {

    @Override
    public ShipmentResolutionResult resolve(ShipmentEventEntity incoming, ShipmentCurrentStateEntity current) {
        ShipmentStatus incomingStatus = incoming.getStatus();
        Instant incomingOccurredAt = incoming.getOccurredAt();
        Instant incomingReceivedAt = incoming.getReceivedAt();

        // If no current state, any starting state is valid
        if (current == null) {
            return ShipmentResolutionResult.accepted(
                    incomingStatus,
                    incomingOccurredAt,
                    incomingReceivedAt,
                    incoming.getLocation()
            );
        }

        ShipmentStatus currentStatus = current.getCurrentStatus();
        Instant currentLastOccurredAt = current.getLastOccurredAt();

        // Rule 2 - Out-of-Order Events: If incoming event occurred earlier, don't update state
        if (incomingOccurredAt.isBefore(currentLastOccurredAt)) {
            return ShipmentResolutionResult.noUpdate();
        }

        // Rule 3 - State Transition Validation
        if (!ShipmentStatus.isValidTransition(currentStatus, incomingStatus)) {
            return ShipmentResolutionResult.rejected("INVALID_TRANSITION");
        }

        // Rule 4 - Newer Event Processing: Valid transition, update state
        return ShipmentResolutionResult.accepted(
                incomingStatus,
                incomingOccurredAt,
                incomingReceivedAt,
                incoming.getLocation()
        );
    }
}
