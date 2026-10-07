package com.cabinet.domain;

import com.cabinet.domain.enums.ChannelHealth;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 货道健康度：按货道统计误扣款复核的反复发生情况，区分单次误识别与设备故障。
 * 每个货道一条当前记录（1:1），窗口内误判次数达到阈值自动暂停货道售卖并生成维护告警。
 */
@Entity
@Table(name = "channel_health_record",
        uniqueConstraints = @UniqueConstraint(columnNames = "channel_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelHealthRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ChannelHealth health = ChannelHealth.HEALTHY;

    /** 滚动窗口起点（窗口外的误判不再计入） */
    private LocalDateTime windowStart;

    /** 窗口内判定为设备误识别（复核退款）次数 */
    @Builder.Default
    private int miscountInWindow = 0;

    /** 累计误判次数（不随窗口重置） */
    @Builder.Default
    private int miscountTotal = 0;

    /** 最近一次误判的复核单号 */
    private String lastReviewNo;

    private LocalDateTime lastMiscountAt;

    private LocalDateTime suspendedAt;

    @Column(length = 500)
    private String suspendReason;
}
