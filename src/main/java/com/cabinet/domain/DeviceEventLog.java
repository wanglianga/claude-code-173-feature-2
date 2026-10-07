package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 设备事件接收日志：按设备事件 ID 幂等去重。
 * 设备重试、离线恢复后集中补传、网络重复投递都会产生重复事件，
 * 同一 eventId 只处理一次，后续仅累加 dupCount，不重复触发结算/退款。
 */
@Entity
@Table(name = "device_event_log",
        uniqueConstraints = @UniqueConstraint(columnNames = "eventId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 设备侧事件唯一 ID（未上报时服务端生成 AUTO- 前缀 ID，无法去重仅留痕） */
    private String eventId;

    private String type;
    private String cabinetNo;
    private String sessionNo;

    /** 设备侧事件发生时间 */
    private LocalDateTime eventTime;

    /** 服务端接收时间 */
    private LocalDateTime receivedAt;

    /** 是否补传（设备离线后集中上报，或事件时间明显滞后） */
    @Builder.Default
    private boolean backfill = false;

    /** 重复投递次数（同一 eventId 再次到达只计数不处理） */
    @Builder.Default
    private Integer dupCount = 0;

    @Column(length = 500)
    private String note;
}
