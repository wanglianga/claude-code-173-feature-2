package com.cabinet.domain.enums;

public enum AlertType {
    WRONG_CHANNEL,      // 放错货道
    CHANNEL_JAM,        // 货道卡货
    TEMP_ALARM,         // 冷藏温度异常
    NEAR_EXPIRY,        // 临期未下架预警
    EXPIRED,            // 过期
    STOCK_DISCREPANCY,  // 盘点差异
    RECOGNITION_CONFLICT, // 识别存疑
    CHANNEL_HEALTH      // 同一货道反复误扣款投诉（疑似设备故障）
}
