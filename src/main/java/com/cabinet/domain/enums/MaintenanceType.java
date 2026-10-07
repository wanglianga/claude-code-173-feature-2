package com.cabinet.domain.enums;

/** 维护工单类型 */
public enum MaintenanceType {
    INSPECT,    // 检查（疑似故障排查）
    REPAIR,     // 维修
    REPLACE     // 换机（更换货道设备，保留历史记录）
}
