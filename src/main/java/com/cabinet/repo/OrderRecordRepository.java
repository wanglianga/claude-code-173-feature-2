package com.cabinet.repo;

import com.cabinet.domain.OrderRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {
    Optional<OrderRecord> findByOrderNo(String orderNo);
    Optional<OrderRecord> findBySessionId(Long sessionId);
    List<OrderRecord> findByCabinetId(Long cabinetId);
    List<OrderRecord> findByCabinetIdIn(List<Long> cabinetIds);

    /** 退款执行时行级锁订单，串行化同一订单的重复/并发退款点击 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderRecord o where o.id = :id")
    Optional<OrderRecord> lockById(@Param("id") Long id);
}
