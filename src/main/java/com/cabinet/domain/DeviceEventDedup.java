package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 设备事件幂等去重：柜机离线恢复后集中补传、网络重试都可能造成同一事件重复到达。
 * event_key 唯一约束保证同一 (sessionNo,type,channelCode,sku,action,detectedAt) 事件只生效一次，
 * 重复事件直接忽略并标记，绝不允许重复扣款/重复写识别样本。
 */
@Entity
@Table(name = "device_event_dedup",
        uniqueConstraints = @UniqueConstraint(columnNames = "event_key"),
        indexes = @Index(columnList = "session_no"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceEventDedup extends BaseEntity {

    /** 业务指纹（见 DeviceEventProcessor.eventKey） */
    private String eventKey;

    private String sessionNo;
    private String cabinetNo;
    private String eventType;

    /** 是否为关门后补传（离线集中上报） */
    private boolean lateArrival;

    /** 首次到达时间 */
    private LocalDateTime firstArrivedAt;

    /** 重复到达次数 */
    @Builder.Default
    private int duplicateCount = 0;

    private LocalDateTime lastDuplicateAt;
}
