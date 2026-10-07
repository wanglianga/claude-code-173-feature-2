package com.cabinet.repo;

import com.cabinet.domain.ReviewAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewAuditRepository extends JpaRepository<ReviewAudit, Long> {
    List<ReviewAudit> findByRefTypeAndRefNoOrderByIdAsc(String refType, String refNo);
}
