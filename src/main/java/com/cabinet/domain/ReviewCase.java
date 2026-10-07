package com.cabinet.domain;

import com.cabinet.domain.enums.ReviewStatus;
import com.cabinet.domain.enums.ReviewVerdict;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 误扣款复核单：一次开门扣款是否成立的人工复核载体。
 * 把开门时间、拿取识别、重量变化、视觉识别、货道盘点、扣款时间聚合在同一视图，
 * 系统先给出初判（证据充分/疑似误判/信号冲突），冲突一律转人工。
 * 原始判定（系统初判、原始证据快照）在整单生命周期内不可变，后续修正以操作留痕追加。
 */
@Entity
@Table(name = "review_case",
        uniqueConstraints = @UniqueConstraint(columnNames = "session_id"),
        indexes = {
                @Index(columnList = "cabinet_id"),
                @Index(columnList = "status"),
                @Index(columnList = "channel_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewCase extends BaseEntity {

    private String reviewNo;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private DoorSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cabinet cabinet;

    /** 复核聚焦货道（投诉描述的拿起放回货道，可空表示整会话） */
    @ManyToOne(fetch = FetchType.LAZY)
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY)
    private OrderRecord order;

    /** 触发本复核的投诉单（可空：运营也可对存疑订单直接发起复核） */
    @ManyToOne(fetch = FetchType.LAZY)
    private Complaint complaint;

    private String userCode;
    private String userName;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ReviewStatus status = ReviewStatus.MANUAL_REVIEW;

    /** 系统初判（创建时固化，永不覆盖） */
    @Enumerated(EnumType.STRING)
    private ReviewVerdict initialVerdict;

    /** 初判理由（冲突的传感器、重量恢复等机器可读说明） */
    @Column(length = 1000)
    private String initialReason;

    /** 初判时的证据快照 JSON：开门/识别/重量/盘点/扣款时间，防止后续补传改写历史结论 */
    @Column(length = 8000)
    private String evidenceSnapshotJson;

    /** 人工最终结论（同 ReviewVerdict 取值，由客服/运营填写） */
    @Enumerated(EnumType.STRING)
    private ReviewVerdict finalVerdict;

    @Column(length = 1000)
    private String conclusion;

    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser handler;

    /** 退款金额（维持扣款/驳回时为 0） */
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal refundAmount = BigDecimal.ZERO;

    /** 商品是否收回 */
    private boolean goodsReturned;

    /** 关联退款流水号 */
    private String refundNo;

    /** 设备离线后补传数据是否在复核期间到达 */
    @Builder.Default
    private boolean lateDataArrived = false;

    @Column(length = 500)
    private String lateDataNote;

    /** 货道换机时记录新柜机编号，原始证据仍挂旧柜机旧会话 */
    private String replacedCabinetNo;

    private LocalDateTime openedAt;
    private LocalDateTime chargedAt;
    private LocalDateTime reviewedAt;

    /** 幂等：同一投诉不得重复发起复核 */
    private Long sourceComplaintId;
}
