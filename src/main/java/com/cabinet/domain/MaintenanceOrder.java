package com.cabinet.domain;

import com.cabinet.domain.enums.MaintenanceStatus;
import com.cabinet.domain.enums.MaintenanceType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 货道维护工单：同一货道反复误扣款投诉（疑似设备故障）时自动生成，
 * 也可人工创建。支持检查/维修/换机；换机记录新旧设备序列号，历史记录保留。
 */
@Entity
@Table(name = "maintenance_order",
        indexes = @Index(columnList = "status"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaintenanceOrder extends BaseEntity {

    private String moNo;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cabinet cabinet;

    @ManyToOne(fetch = FetchType.LAZY)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    private MaintenanceType type;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private MaintenanceStatus status = MaintenanceStatus.OPEN;

    /** 是否由系统（货道健康监控）自动创建 */
    @Builder.Default
    private boolean autoCreated = false;

    /** 触发原因（如：7 天内 3 次误扣款退款投诉） */
    @Column(length = 1000)
    private String reason;

    /** 换机前设备序列号（快照） */
    private String oldEquipmentSn;

    /** 换机后设备序列号 */
    private String newEquipmentSn;

    private String operatorName;

    @Column(length = 1000)
    private String resolution;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
