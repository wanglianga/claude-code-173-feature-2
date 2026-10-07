package com.cabinet.domain.enums;

/**
 * 误扣款复核单状态。
 * AUTO_PENDING：系统初判证据充分，待客服确认扣款依据；
 * MANUAL_REVIEW：重量与视觉等信号冲突，必须人工判定（不得自动出结论）；
 * UPHELD：复核维持原扣款（向用户展示扣款依据）；
 * REFUNDED：复核确认误判，已退款纠正；
 * REJECTED：申诉被驳回（不支持退款）。
 */
public enum ReviewStatus {
    AUTO_PENDING,
    MANUAL_REVIEW,
    UPHELD,
    REFUNDED,
    REJECTED
}
