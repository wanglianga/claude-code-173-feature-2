package com.cabinet.domain.enums;

public enum AlertType {
    WRONG_CHANNEL,      // 放错货道
    CHANNEL_JAM,        // 货道卡货
    TEMP_ALARM,         // 冷藏温度异常
    NEAR_EXPIRY,        // 临期未下架预警
    EXPIRED,            // 过期
    STOCK_DISCREPANCY,  // 盘点差异
    RECOGNITION_CONFLICT, // 识别存疑
    MISCHARGE_REVIEW,   // 误扣款复核（疑似误判，转人工）
    CHANNEL_SUSPECT,    // 货道反复误识别，观察
    CHANNEL_SUSPENDED,  // 货道疑似设备故障，暂停售卖待维护
    LATE_DEVICE_DATA    // 设备离线后集中补传数据
}
