package com.accso.shipment.repository;

import com.accso.shipment.entity.ShipmentCurrentStateEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ShipmentCurrentStateRepository extends JpaRepository<ShipmentCurrentStateEntity, String> {

    List<ShipmentCurrentStateEntity> findByUpdatedAtBefore(Instant cutoff, Pageable pageable);
}
