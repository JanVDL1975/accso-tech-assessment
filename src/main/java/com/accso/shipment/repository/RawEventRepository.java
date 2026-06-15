package com.accso.shipment.repository;

import com.accso.shipment.entity.RawEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface RawEventRepository extends JpaRepository<RawEventEntity, Long> {

    boolean existsByEventIdAndPartner(String eventId, String partner);

    List<RawEventEntity> findByReceivedAtBefore(Instant cutoff, Pageable pageable);
}
