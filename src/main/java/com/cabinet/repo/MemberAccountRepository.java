package com.cabinet.repo;

import com.cabinet.domain.MemberAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MemberAccountRepository extends JpaRepository<MemberAccount, Long> {
    Optional<MemberAccount> findByUserCode(String userCode);

    /** 余额变更行级锁，保证扣款/退款串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MemberAccount a where a.userCode = :userCode")
    Optional<MemberAccount> lockByUserCode(@Param("userCode") String userCode);
}
