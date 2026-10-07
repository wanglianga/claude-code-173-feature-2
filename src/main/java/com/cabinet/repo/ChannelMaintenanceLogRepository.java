package com.cabinet.repo;

import com.cabinet.domain.ChannelMaintenanceLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChannelMaintenanceLogRepository extends JpaRepository<ChannelMaintenanceLog, Long> {
    List<ChannelMaintenanceLog> findByChannelIdOrderByIdDesc(Long channelId);
}
