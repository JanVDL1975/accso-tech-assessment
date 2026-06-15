package com.accso.shipment.service;

import com.accso.shipment.domain.ShipmentStatus;
import com.accso.shipment.entity.AuditLogEntity;
import com.accso.shipment.entity.RawEventEntity;
import com.accso.shipment.entity.ShipmentCurrentStateEntity;
import com.accso.shipment.repository.AuditLogRepository;
import com.accso.shipment.repository.RawEventRepository;
import com.accso.shipment.repository.ShipmentCurrentStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scheduled service for retention cleanup.
 * - Raw events: deleted after 30 days (except terminal-state shipments)
 * - Audit log: deleted after 1 year (except terminal-state shipments)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionCleanupService {

    private final RawEventRepository rawEventRepository;
    private final AuditLogRepository auditLogRepository;
    private final ShipmentCurrentStateRepository currentStateRepository;

    @Value("${retention.raw-event-days:30}")
    private int rawEventRetentionDays;

    @Value("${retention.audit-log-days:365}")
    private int auditLogRetentionDays;

    @Value("${retention.batch-size:1000}")
    private int batchSize;

    private static final Set<ShipmentStatus> TERMINAL_STATES = Set.of(
            ShipmentStatus.DELIVERED,
            ShipmentStatus.RETURNED
    );

    /**
     * Purges raw_events older than 30 days.
     * Events for terminal-state shipments (DELIVERED/RETURNED) are retained indefinitely.
     * Runs daily at 2:00 AM.
     */
    @Scheduled(cron = "${retention.raw-event-cron:0 0 2 * * *}")
    public void purgeRawEvents() {
        Instant cutoff = Instant.now().minus(rawEventRetentionDays, ChronoUnit.DAYS);
        log.info("Starting raw event purge. Cutoff: {}, batchSize: {}", cutoff, batchSize);

        int totalPurged = 0;
        List<RawEventEntity> eventsToCheck;

        do {
            eventsToCheck = rawEventRepository.findByReceivedAtBefore(cutoff, PageRequest.of(0, batchSize));

            if (eventsToCheck.isEmpty()) {
                break;
            }

            List<String> shipmentIds = eventsToCheck.stream()
                    .map(RawEventEntity::getShipmentId)
                    .distinct()
                    .toList();

            List<ShipmentCurrentStateEntity> currentStates = currentStateRepository.findAllById(shipmentIds);

            Set<String> terminalShipmentIds = currentStates.stream()
                    .filter(s -> TERMINAL_STATES.contains(s.getCurrentStatus()))
                    .map(ShipmentCurrentStateEntity::getShipmentId)
                    .collect(Collectors.toSet());

            List<RawEventEntity> toDelete = eventsToCheck.stream()
                    .filter(e -> !terminalShipmentIds.contains(e.getShipmentId()))
                    .toList();

            if (!toDelete.isEmpty()) {
                rawEventRepository.deleteAll(toDelete);
                totalPurged += toDelete.size();
                log.debug("Purged {} raw events in this batch", toDelete.size());
            }

        } while (eventsToCheck.size() == batchSize);

        log.info("Raw event purge completed. Total purged: {}", totalPurged);
    }

    /**
     * Purges audit_log entries older than 1 year.
     * Entries for terminal-state shipments (DELIVERED/RETURNED) are retained indefinitely.
     * Runs daily at 4:00 AM.
     */
    @Scheduled(cron = "${retention.audit-log-cron:0 0 4 * * *}")
    public void purgeAuditLog() {
        Instant cutoff = Instant.now().minus(auditLogRetentionDays, ChronoUnit.DAYS);
        log.info("Starting audit log purge. Cutoff: {}, batchSize: {}", cutoff, batchSize);

        int totalPurged = 0;
        List<AuditLogEntity> entriesToCheck;

        do {
            entriesToCheck = auditLogRepository.findByCreatedAtBefore(cutoff, PageRequest.of(0, batchSize));

            if (entriesToCheck.isEmpty()) {
                break;
            }

            List<String> shipmentIds = entriesToCheck.stream()
                    .map(AuditLogEntity::getShipmentId)
                    .distinct()
                    .toList();

            List<ShipmentCurrentStateEntity> currentStates = currentStateRepository.findAllById(shipmentIds);

            Set<String> terminalShipmentIds = currentStates.stream()
                    .filter(s -> TERMINAL_STATES.contains(s.getCurrentStatus()))
                    .map(ShipmentCurrentStateEntity::getShipmentId)
                    .collect(Collectors.toSet());

            List<AuditLogEntity> toDelete = entriesToCheck.stream()
                    .filter(e -> !terminalShipmentIds.contains(e.getShipmentId()))
                    .toList();

            if (!toDelete.isEmpty()) {
                auditLogRepository.deleteAll(toDelete);
                totalPurged += toDelete.size();
                log.debug("Purged {} audit log entries in this batch", toDelete.size());
            }

        } while (entriesToCheck.size() == batchSize);

        log.info("Audit log purge completed. Total purged: {}", totalPurged);
    }
}
