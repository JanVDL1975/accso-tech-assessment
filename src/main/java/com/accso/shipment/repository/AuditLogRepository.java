package com.accso.shipment.repository;

import com.accso.shipment.entity.AuditLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByShipmentIdOrderByCreatedAtAsc(String shipmentId);

    List<AuditLogEntity> findByCreatedAtBefore(Instant cutoff, Pageable pageable);
}
