package com.cabinet.domain.enums;

/**
 * 货道健康度：
 * HEALTHY 正常；SUSPECT 观察（窗口内出现少量误判投诉，单次误识别）；
 * SUSPENDED 暂停售卖（反复投诉判定设备故障，待维护）；MAINTAINED 维护完成恢复。
 */
public enum ChannelHealth {
    HEALTHY,
    SUSPECT,
    SUSPENDED,
    MAINTAINED
}
