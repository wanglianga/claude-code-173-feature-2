package com.cabinet.domain;

import com.cabinet.domain.enums.ComplaintStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户投诉/售后单：关联开门会话，客服凭证据回放判责并退款 */
@Entity
@Table(name = "complaint")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Complaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String complaintNo;

    @ManyToOne(fetch = FetchType.LAZY)
    private DoorSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    private OrderRecord order;

    private String userCode;
    private String userName;

    /** 投诉类型：误扣款/放回仍计费/临期变质/卡货未出货/其他 */
    private String category;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ComplaintStatus status = ComplaintStatus.SUBMITTED;

    /** 客服处理人 */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser handler;

    /** 退款金额 */
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal refundAmount = BigDecimal.ZERO;

    /** 退款条目/批次的 JSON 快照 */
    @Column(length = 2000)
    private String refundDetailJson;

    @Column(length = 2000)
    private String resolution;

    /** 重复申诉：指向同一会话的首张投诉单 ID（保留每次申诉与处理过程） */
    private Long duplicateOfId;

    /** 提交时系统研判建议快照（EVIDENCE_SUFFICIENT / SUSPECT_MISCHARGE / NEED_MANUAL） */
    private String systemVerdict;

    private LocalDateTime submittedAt;
    private LocalDateTime resolvedAt;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.submittedAt == null) this.submittedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
