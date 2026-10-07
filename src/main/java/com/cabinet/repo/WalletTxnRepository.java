package com.cabinet.repo;

import com.cabinet.domain.WalletTxn;
import com.cabinet.domain.enums.WalletTxnType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WalletTxnRepository extends JpaRepository<WalletTxn, Long> {
    Optional<WalletTxn> findByTxnNo(String txnNo);

    /** 幂等键：同一业务单据同一类型只入账一次 */
    Optional<WalletTxn> findByBizTypeAndBizIdAndTxnType(String bizType, Long bizId, WalletTxnType txnType);

    List<WalletTxn> findByUserCodeOrderByIdDesc(String userCode);
}
