package com.cabinet.domain;

import com.cabinet.domain.enums.WalletTxnType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * 钱包流水：扣款/退款逐笔记录。
 * (bizType, bizId, txnType) 唯一约束是幂等底线：
 * 同一订单只扣一次款、同一投诉单只退一次款，重复点击/设备补传/消息重投都无法二次落账。
 */
@Entity
@Table(name = "wallet_txn",
        uniqueConstraints = @UniqueConstraint(columnNames = {"bizType", "bizId", "txnType"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletTxn extends BaseEntity {

    private String txnNo;

    private String userCode;

    @Enumerated(EnumType.STRING)
    private WalletTxnType txnType;

    /** 发生额（正数；方向由 txnType 决定） */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    /** 本笔后的账户余额 */
    @Column(precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    /** 关联业务：ORDER（扣款）/ COMPLAINT（退款） */
    private String bizType;
    private Long bizId;

    @Column(length = 500)
    private String remark;
}
