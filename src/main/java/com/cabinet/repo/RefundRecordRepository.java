package com.cabinet.repo;

import com.cabinet.domain.RefundRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefundRecordRepository extends JpaRepository<RefundRecord, Long> {
    Optional<RefundRecord> findByIdempotencyKey(String idempotencyKey);
    List<RefundRecord> findByOrderIdOrderByIdAsc(Long orderId);
}
