package com.cabinet.domain;

import com.cabinet.domain.enums.AccountTxnType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * 账户流水：扣款为负、退款回补/充值为正。
 * idempotency_key 唯一约束：同一笔业务（订单/退款单）只能产生一次资金变动，
 * 重复点击与消息补传不会造成二次退款或重复扣款。
 */
@Entity
@Table(name = "account_txn",
        uniqueConstraints = @UniqueConstraint(columnNames = "idempotency_key"),
        indexes = @Index(columnList = "user_code,created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountTxn extends BaseEntity {

    private String userCode;

    @Enumerated(EnumType.STRING)
    private AccountTxnType type;

    /** 正=入账（退款/充值），负=扣款 */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    /** 变动后余额 */
    @Column(precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    private String idempotencyKey;
    private String bizRefNo;

    @Column(length = 500)
    private String remark;
}
