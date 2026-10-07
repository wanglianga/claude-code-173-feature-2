package com.cabinet.repo;

import com.cabinet.domain.Complaint;
import com.cabinet.domain.enums.ComplaintStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
    Optional<Complaint> findByComplaintNo(String complaintNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Complaint c where c.complaintNo = :complaintNo")
    Optional<Complaint> lockByComplaintNo(@Param("complaintNo") String complaintNo);

    List<Complaint> findBySessionIdOrderByIdAsc(Long sessionId);

    List<Complaint> findByStatusIn(List<ComplaintStatus> statuses);

    /** 货道健康统计：窗口内某柜机某货道「误扣款退款成立」的投诉数（按退款行归责到货道） */
    @Query("select count(distinct c) from Complaint c, OrderItem oi " +
           "where oi.order = c.order and oi.channelCode = :channelCode and oi.refundedQty > 0 " +
           "and c.order.cabinet.id = :cabinetId and c.status = com.cabinet.domain.enums.ComplaintStatus.RESOLVED_REFUNDED " +
           "and c.resolvedAt > :since")
    long countRefundedByChannelSince(@Param("cabinetId") Long cabinetId,
                                     @Param("channelCode") String channelCode,
                                     @Param("since") LocalDateTime since);
}
