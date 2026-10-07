package com.cabinet.service;

import com.cabinet.domain.AccountTxn;
import com.cabinet.domain.MemberAccount;
import com.cabinet.domain.enums.AccountTxnType;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.AccountTxnRepository;
import com.cabinet.repo.MemberAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 会员账户资金服务：购物扣款与退款回补。
 * 每笔资金变动带业务幂等键（ORDER:xxx / REFUND:xxx），
 * 账户行级悲观锁 + 流水唯一约束双重保证：重复点击、消息重投、设备补传都不会二次动账。
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final MemberAccountRepository accountRepository;
    private final AccountTxnRepository txnRepository;

    /** 关联合员账户（不存在则开户，初始余额 0） */
    @Transactional
    public MemberAccount ensureAccount(String userCode, String userName) {
        return accountRepository.findByUserCode(userCode).orElseGet(() ->
                accountRepository.save(MemberAccount.builder()
                        .userCode(userCode).userName(userName)
                        .balance(BigDecimal.ZERO).build()));
    }

    @Transactional(readOnly = true)
    public MemberAccount get(String userCode) {
        return accountRepository.findByUserCode(userCode)
                .orElseThrow(() -> ApiException.notFound("会员账户不存在: " + userCode));
    }

    @Transactional(readOnly = true)
    public List<AccountTxn> txns(String userCode) {
        return txnRepository.findByUserCodeOrderByIdDesc(userCode);
    }

    @Transactional
    public MemberAccount recharge(String userCode, String userName, BigDecimal amount, String remark) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("充值金额必须大于 0");
        }
        ensureAccount(userCode, userName);
        postTxn(userCode, AccountTxnType.RECHARGE, amount,
                "RECHARGE:" + userCode + ":" + System.nanoTime(), userCode, remark);
        return get(userCode);
    }

    /** 购物扣款（幂等：同一订单只扣一次） */
    @Transactional
    public AccountTxn charge(String userCode, BigDecimal amount, String idempotencyKey,
                             String bizRefNo, String remark) {
        return postTxn(userCode, AccountTxnType.CHARGE, amount.negate(),
                idempotencyKey, bizRefNo, remark);
    }

    /** 退款回补（幂等：同一退款单只回补一次） */
    @Transactional
    public AccountTxn refund(String userCode, BigDecimal amount, String idempotencyKey,
                             String bizRefNo, String remark) {
        if (amount.signum() <= 0) {
            return null;
        }
        return postTxn(userCode, AccountTxnType.REFUND, amount, idempotencyKey, bizRefNo, remark);
    }

    private AccountTxn postTxn(String userCode, AccountTxnType type, BigDecimal signedAmount,
                               String idempotencyKey, String bizRefNo, String remark) {
        // 幂等快路径：流水已存在直接返回，不再动账
        AccountTxn existing = txnRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return existing;
        }
        try {
            MemberAccount account = accountRepository.lockByUserCode(userCode)
                    .orElseGet(() -> accountRepository.save(MemberAccount.builder()
                            .userCode(userCode).balance(BigDecimal.ZERO).build()));
            // 锁内复查，防止并发双花
            existing = txnRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null) {
                return existing;
            }
            BigDecimal after = account.getBalance().add(signedAmount);
            account.setBalance(after);
            accountRepository.save(account);
            return txnRepository.save(AccountTxn.builder()
                    .userCode(userCode).type(type).amount(signedAmount)
                    .balanceAfter(after).idempotencyKey(idempotencyKey)
                    .bizRefNo(bizRefNo).remark(remark).build());
        } catch (DataIntegrityViolationException e) {
            // 唯一约束兜底：并发下另一个事务已写入同键流水
            return txnRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e);
        }
    }
}
