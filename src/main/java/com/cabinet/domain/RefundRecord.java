package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款流水：每一笔资金退付都落表，idempotency_key 唯一。
 * 复核单整单退、按行退、投诉直退分别使用不同前缀的幂等键，
 * 配合行级悲观锁保证重复点击/并发提交/设备补传均不会二次退款。
 */
@Entity
@Table(name = "refund_record",
        uniqueConstraints = @UniqueConstraint(columnNames = "idempotency_key"),
        indexes = @Index(columnList = "order_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundRecord extends BaseEntity {

    private String refundNo;

    @ManyToOne(fetch = FetchType.LAZY)
    private OrderRecord order;

    /** 来源复核单（可为空，兼容无复核单的投诉直退） */
    @ManyToOne(fetch = FetchType.LAZY)
    private ReviewCase reviewCase;

    @ManyToOne(fetch = FetchType.LAZY)
    private Complaint complaint;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    private String idempotencyKey;

    /** 支付/退款渠道流水号 */
    private String refundTxnNo;

    /** 商品是否收回：收回回补库存，未收回登记报损 */
    private boolean goodsReturned;

    /** 退款明细 JSON：sku/批次/货道/数量/金额 */
    @Column(length = 2000)
    private String detailJson;

    /** 退款时订单是否已被供应商结算单覆盖（已结算订单的跨期修正说明） */
    @Column(length = 500)
    private String settlementImpact;

    private LocalDateTime refundedAt;
}
