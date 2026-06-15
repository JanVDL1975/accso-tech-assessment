package com.accso.shipment.repository;

import com.accso.shipment.entity.ShipmentEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShipmentEventRepository extends JpaRepository<ShipmentEventEntity, Long> {

    Optional<ShipmentEventEntity> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    boolean existsByEventIdAndPartner(String eventId, String partner);

    List<ShipmentEventEntity> findByShipmentIdOrderByReceivedAtAsc(String shipmentId);

    List<ShipmentEventEntity> findByReceivedAtBefore(Instant cutoff, Pageable pageable);
}
