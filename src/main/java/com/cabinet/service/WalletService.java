package com.cabinet.service;

import com.cabinet.domain.WalletAccount;
import com.cabinet.domain.WalletTxn;
import com.cabinet.domain.enums.WalletTxnType;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.WalletAccountRepository;
import com.cabinet.repo.WalletTxnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 用户账户余额服务：订单扣款借记、投诉复核退款贷记。
 * 幂等底线：(bizType, bizId, txnType) 唯一约束 —— 同一订单只扣一次、同一投诉只退一次，
 * 重复点击/重复投递/设备补传均无法造成二次扣退款。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletAccountRepository accountRepository;
    private final WalletTxnRepository txnRepository;

    /** 订单扣款（借记）。同一订单重复调用直接返回原流水，不重复扣款。 */
    @Transactional
    public WalletTxn charge(String userCode, BigDecimal amount, String bizType, Long bizId, String remark) {
        return post(userCode, amount, WalletTxnType.CHARGE, bizType, bizId, remark);
    }

    /** 复核退款（贷记）。同一投诉单重复调用直接返回原流水，不重复退款。 */
    @Transactional
    public WalletTxn refund(String userCode, BigDecimal amount, String bizType, Long bizId, String remark) {
        return post(userCode, amount, WalletTxnType.REFUND, bizType, bizId, remark);
    }

    private WalletTxn post(String userCode, BigDecimal amount, WalletTxnType type,
                           String bizType, Long bizId, String remark) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("钱包流水金额必须大于 0");
        }
        // 幂等：已入账直接返回原流水
        var existing = txnRepository.findByBizTypeAndBizIdAndTxnType(bizType, bizId, type);
        if (existing.isPresent()) {
            log.info("钱包流水幂等命中 {} {} {}，跳过重复入账", bizType, bizId, type);
            return existing.get();
        }
        WalletAccount account = accountRepository.lockByUserCode(userCode)
                .orElseGet(() -> accountRepository.save(WalletAccount.builder()
                        .userCode(userCode).balance(BigDecimal.ZERO).build()));
        BigDecimal signed = type == WalletTxnType.REFUND ? amount : amount.negate();
        BigDecimal after = account.getBalance().add(signed);
        account.setBalance(after);
        accountRepository.save(account);

        WalletTxn txn = WalletTxn.builder()
                .txnNo(genNo())
                .userCode(userCode)
                .txnType(type)
                .amount(amount)
                .balanceAfter(after)
                .bizType(bizType).bizId(bizId)
                .remark(remark)
                .build();
        try {
            return txnRepository.saveAndFlush(txn);
        } catch (DataIntegrityViolationException e) {
            // 并发下唯一约束兜底：返回已存在流水，绝不二次入账
            return txnRepository.findByBizTypeAndBizIdAndTxnType(bizType, bizId, type)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public WalletAccount accountOf(String userCode) {
        return accountRepository.findByUserCode(userCode)
                .orElseThrow(() -> ApiException.notFound("用户钱包不存在: " + userCode));
    }

    @Transactional(readOnly = true)
    public List<WalletTxn> txnsOf(String userCode) {
        return txnRepository.findByUserCodeOrderByIdDesc(userCode);
    }

    private String genNo() {
        return "WT" + LocalDate.now().toString().replace("-", "")
                + String.format("%06d", System.nanoTime() % 1000000);
    }
}
