package com.cabinet.domain;

import com.cabinet.domain.enums.MaintenanceType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 货道维护留痕：暂停售卖、维护恢复、设备换机。
 * 换机时把旧柜机货道的健康档案/维护历史迁移说明记录在此，原始会话与证据仍归属旧柜机，
 * 满足“货道换机也要保留原始判断和后续修正过程”。
 */
@Entity
@Table(name = "channel_maintenance_log",
        indexes = @Index(columnList = "channel_id,id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelMaintenanceLog extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    private Channel channel;

    private String cabinetNo;

    @Enumerated(EnumType.STRING)
    private MaintenanceType type;

    /** 换机时的目标柜机编号/货道编码 */
    private String targetCabinetNo;
    private String targetChannelCode;

    private String operator;

    @Column(length = 1000)
    private String remark;

    private LocalDateTime actionAt;
}
