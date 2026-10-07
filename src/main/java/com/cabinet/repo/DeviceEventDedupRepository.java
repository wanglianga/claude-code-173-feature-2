package com.cabinet.repo;

import com.cabinet.domain.DeviceEventDedup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceEventDedupRepository extends JpaRepository<DeviceEventDedup, Long> {
    Optional<DeviceEventDedup> findByEventKey(String eventKey);
}
