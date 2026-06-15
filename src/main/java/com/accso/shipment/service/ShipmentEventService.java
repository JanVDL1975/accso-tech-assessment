package com.accso.shipment.service;

import com.accso.shipment.dto.EventIngestionResponse;
import com.accso.shipment.dto.ShipmentEventRequest;
import com.accso.shipment.dto.ShipmentEventResponse;
import com.accso.shipment.dto.ShipmentStatusResponse;
import com.accso.shipment.entity.ShipmentCurrentStateEntity;
import com.accso.shipment.entity.ShipmentEventEntity;
import com.accso.shipment.repository.ShipmentCurrentStateRepository;
import com.accso.shipment.repository.ShipmentEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service for handling shipment event ingestion and state management.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentEventService {

    private final ShipmentEventRepository eventRepository;
    private final ShipmentCurrentStateRepository currentStateRepository;
    private final ShipmentStateResolver stateResolver;

    /**
     * Process incoming shipment event.
     * Flow:
     * 1. Check for duplicate event
     * 2. Resolve state transition
     * 3. If accepted: persist to derived_events and update current state
     */
    @Transactional
    public EventIngestionResponse receiveEvent(ShipmentEventRequest request) {
        String eventId = request.getEventId();
        String partner = request.getPartner();

        // Step 1: Duplicate check
        if (eventRepository.existsByEventIdAndPartner(eventId, partner)) {
            log.info("Duplicate event detected: eventId={}, partner={}", eventId, partner);
            return EventIngestionResponse.duplicate(eventId);
        }

        // Load current state for this shipment
        Optional<ShipmentCurrentStateEntity> currentStateOpt =
                currentStateRepository.findById(request.getShipmentId());
        ShipmentCurrentStateEntity currentState = currentStateOpt.orElse(null);

        // Step 2: Resolve state transition
        ShipmentResolutionResult result = stateResolver.resolve(
                toEventEntity(request), currentState);

        // Step 3: If rejected, return early
        if (!result.isAccepted()) {
            return EventIngestionResponse.rejected(eventId, result.getRejectionReason());
        }

        // Persist to derived_events
        ShipmentEventEntity eventEntity = toEventEntity(request);
        eventEntity.setCreatedAt(Instant.now());
        eventRepository.save(eventEntity);

        // Update current state if result contains new state
        if (result.getNewStatus() != null) {
            updateCurrentState(request.getShipmentId(), result);
        }

        return EventIngestionResponse.accepted(eventId);
    }

    private ShipmentEventEntity toEventEntity(ShipmentEventRequest request) {
        return ShipmentEventEntity.builder()
                .eventId(request.getEventId())
                .shipmentId(request.getShipmentId())
                .partner(request.getPartner())
                .status(request.getStatus())
                .occurredAt(request.getOccurredAt())
                .receivedAt(request.getReceivedAt())
                .location(request.getLocation())
                .build();
    }

    private void updateCurrentState(String shipmentId, ShipmentResolutionResult result) {
        ShipmentCurrentStateEntity state = ShipmentCurrentStateEntity.builder()
                .shipmentId(shipmentId)
                .currentStatus(result.getNewStatus())
                .lastOccurredAt(result.getNewLastOccurredAt())
                .lastReceivedAt(result.getNewLastReceivedAt())
                .location(result.getNewLocation())
                .updatedAt(Instant.now())
                .build();

        currentStateRepository.save(state);
    }

    /**
     * Get current shipment status.
     */
    @Transactional(readOnly = true)
    public Optional<ShipmentStatusResponse> getShipmentStatus(String shipmentId) {
        return currentStateRepository.findById(shipmentId)
                .map(state -> ShipmentStatusResponse.builder()
                        .shipmentId(state.getShipmentId())
                        .currentStatus(state.getCurrentStatus())
                        .lastReceivedAt(state.getLastReceivedAt())
                        .location(state.getLocation())
                        .build());
    }

    /**
     * Get full event history for a shipment, ordered by receivedAt ascending.
     * Queries the derived_events table.
     */
    @Transactional(readOnly = true)
    public List<ShipmentEventResponse> getEventHistory(String shipmentId) {
        return eventRepository.findByShipmentIdOrderByReceivedAtAsc(shipmentId)
                .stream()
                .map(event -> ShipmentEventResponse.builder()
                        .eventId(event.getEventId())
                        .shipmentId(event.getShipmentId())
                        .partner(event.getPartner())
                        .status(event.getStatus())
                        .occurredAt(event.getOccurredAt())
                        .receivedAt(event.getReceivedAt())
                        .location(event.getLocation())
                        .build())
                .toList();
    }
}
