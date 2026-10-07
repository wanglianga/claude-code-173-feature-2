package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * 用户账户余额（按用户标识 userCode 一户）。
 * 关门结算扣款借记、投诉复核退款贷记，全部变动经 wallet_txn 流水幂等落账。
 */
@Entity
@Table(name = "wallet_account",
        uniqueConstraints = @UniqueConstraint(columnNames = "userCode"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletAccount extends BaseEntity {

    /** 用户标识（手机号/会员号，与开门会话 userCode 一致） */
    private String userCode;

    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;
}
