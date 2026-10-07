package com.cabinet.repo;

import com.cabinet.domain.DeviceEventLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceEventLogRepository extends JpaRepository<DeviceEventLog, Long> {
    boolean existsByEventId(String eventId);
    Optional<DeviceEventLog> findByEventId(String eventId);
}
