package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 复核审计日志：误扣款复核全流程留痕。
 * 记录系统原始研判、人工判责、退款/驳回、重复申诉、设备补传、
 * 货道暂停与维护、已结算订单修正等——原始判断与后续修正过程均只追加、不覆盖。
 */
@Entity
@Table(name = "review_audit",
        indexes = @Index(columnList = "refType,refNo"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联对象类型：COMPLAINT / ORDER / SESSION / CHANNEL / DEVICE_EVENT */
    private String refType;

    /** 关联对象单号（投诉单号/订单号/会话号/货道标识/设备事件ID） */
    private String refNo;

    /**
     * 动作：SUBMIT / DUPLICATE_APPEAL / AUTO_ANALYZE / RESOLVE_REFUND / RESOLVE_REJECT /
     * RESOLVE_DUPLICATE_IGNORED / DEVICE_EVENT_DUPLICATE / DEVICE_EVENT_BACKFILL /
     * CHANNEL_SUSPENDED / CHANNEL_RESUMED / MAINTENANCE_CREATED / MAINTENANCE_STARTED /
     * MAINTENANCE_COMPLETED / SETTLED_ORDER_CORRECTION / WALLET_TXN_SKIPPED
     */
    private String action;

    /** 操作人（系统动作为 system） */
    private String actor;

    @Column(length = 2000)
    private String detail;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
