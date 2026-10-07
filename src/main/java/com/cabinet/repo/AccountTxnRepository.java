package com.cabinet.repo;

import com.cabinet.domain.AccountTxn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountTxnRepository extends JpaRepository<AccountTxn, Long> {
    Optional<AccountTxn> findByIdempotencyKey(String idempotencyKey);
    List<AccountTxn> findByUserCodeOrderByIdDesc(String userCode);
}
