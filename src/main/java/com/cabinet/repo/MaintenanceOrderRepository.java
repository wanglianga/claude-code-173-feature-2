package com.cabinet.repo;

import com.cabinet.domain.MaintenanceOrder;
import com.cabinet.domain.enums.MaintenanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaintenanceOrderRepository extends JpaRepository<MaintenanceOrder, Long> {
    Optional<MaintenanceOrder> findByMoNo(String moNo);
    List<MaintenanceOrder> findByStatus(MaintenanceStatus status);
    List<MaintenanceOrder> findByChannelIdAndStatusIn(Long channelId, List<MaintenanceStatus> statuses);
}
