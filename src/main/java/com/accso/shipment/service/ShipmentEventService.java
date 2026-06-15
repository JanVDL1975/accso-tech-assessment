package com.accso.shipment.service;

import com.accso.shipment.dto.AuditLogResponse;
import com.accso.shipment.dto.BatchEventRequest;
import com.accso.shipment.dto.BatchEventResponse;
import com.accso.shipment.dto.EventIngestionResponse;
import com.accso.shipment.dto.ShipmentEventRequest;
import com.accso.shipment.dto.ShipmentEventResponse;
import com.accso.shipment.dto.ShipmentStatusResponse;
import com.accso.shipment.entity.AuditLogEntity;
import com.accso.shipment.entity.AuditLogEntity.AuditDecision;
import com.accso.shipment.entity.RawEventEntity;
import com.accso.shipment.entity.ShipmentCurrentStateEntity;
import com.accso.shipment.entity.ShipmentEventEntity;
import com.accso.shipment.repository.AuditLogRepository;
import com.accso.shipment.repository.RawEventRepository;
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

    private final RawEventRepository rawEventRepository;
    private final ShipmentEventRepository eventRepository;
    private final ShipmentCurrentStateRepository currentStateRepository;
    private final AuditLogRepository auditLogRepository;
    private final ShipmentStateResolver stateResolver;

    /**
     * Process incoming shipment event.
     * Flow:
     * 1. Check for duplicate event in raw_events
     * 2. Persist to raw_events (as-received, before any processing)
     * 3. Resolve state transition
     * 4. Write audit_log entry
     * 5. If accepted: persist to derived_events and update current state
     */
    @Transactional
    public EventIngestionResponse receiveEvent(ShipmentEventRequest request) {
        String eventId = request.getEventId();
        String partner = request.getPartner();

        // Step 1: Duplicate check against raw_events
        if (rawEventRepository.existsByEventIdAndPartner(eventId, partner)) {
            log.info("Duplicate event detected: eventId={}, partner={}", eventId, partner);
            writeAuditLog(request, null, null, AuditDecision.REJECTED, "DUPLICATE_EVENT");
            return EventIngestionResponse.duplicate(eventId);
        }

        // Step 2: Persist to raw_events immediately (as-received)
        RawEventEntity rawEvent = RawEventEntity.builder()
                .eventId(eventId)
                .shipmentId(request.getShipmentId())
                .partner(partner)
                .status(request.getStatus())
                .occurredAt(request.getOccurredAt())
                .receivedAt(request.getReceivedAt())
                .location(request.getLocation())
                .build();
        rawEventRepository.save(rawEvent);

        // Load current state for this shipment
        Optional<ShipmentCurrentStateEntity> currentStateOpt =
                currentStateRepository.findById(request.getShipmentId());
        ShipmentCurrentStateEntity currentState = currentStateOpt.orElse(null);

        // Step 3: Resolve state transition
        ShipmentResolutionResult result = stateResolver.resolve(
                toEventEntity(request), currentState);

        // Step 4: Write audit_log entry
        if (!result.isAccepted()) {
            writeAuditLog(request,
                    currentState != null ? currentState.getCurrentStatus() : null,
                    null,
                    AuditDecision.REJECTED,
                    result.getRejectionReason());
        } else if (result.getNewStatus() == null) {
            writeAuditLog(request,
                    currentState != null ? currentState.getCurrentStatus() : null,
                    null,
                    AuditDecision.NO_UPDATE,
                    null);
        } else {
            writeAuditLog(request,
                    currentState != null ? currentState.getCurrentStatus() : null,
                    result.getNewStatus(),
                    AuditDecision.ACCEPTED,
                    null);
        }

        // Step 5: If rejected, return early
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

    /**
     * Process a batch of events. Each event is processed individually through receiveEvent(),
     * so one bad event does not poison the batch.
     */
    public BatchEventResponse receiveBatchEvents(BatchEventRequest request) {
        List<EventIngestionResponse> results = request.getEvents().stream()
                .map(this::receiveEvent)
                .toList();
        return BatchEventResponse.of(results);
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

    private void writeAuditLog(ShipmentEventRequest request,
                               com.accso.shipment.domain.ShipmentStatus previousStatus,
                               com.accso.shipment.domain.ShipmentStatus newStatus,
                               AuditDecision decision,
                               String rejectionReason) {
        AuditLogEntity auditLog = AuditLogEntity.builder()
                .eventId(request.getEventId())
                .shipmentId(request.getShipmentId())
                .partner(request.getPartner())
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .decision(decision)
                .rejectionReason(rejectionReason)
                .receivedAt(request.getReceivedAt())
                .build();
        auditLogRepository.save(auditLog);
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

    /**
     * Get audit log for a shipment, ordered by createdAt ascending.
     */
    @Transactional(readOnly = true)
    public List<AuditLogResponse> getAuditLog(String shipmentId) {
        return auditLogRepository.findByShipmentIdOrderByCreatedAtAsc(shipmentId)
                .stream()
                .map(audit -> AuditLogResponse.builder()
                        .eventId(audit.getEventId())
                        .shipmentId(audit.getShipmentId())
                        .partner(audit.getPartner())
                        .previousStatus(audit.getPreviousStatus())
                        .newStatus(audit.getNewStatus())
                        .decision(audit.getDecision().name())
                        .rejectionReason(audit.getRejectionReason())
                        .receivedAt(audit.getReceivedAt())
                        .createdAt(audit.getCreatedAt())
                        .build())
                .toList();
    }
}
