package com.cabinet.domain.enums;

/**
 * 复核初判/人工结论：
 * CONFIRM_SUGGESTED：多源证据一致且称重匹配，证据充分（建议维持扣款并向用户展示依据）；
 * REFUND_SUGGESTED：重量已恢复但视觉仍判定取走等误判特征（不得直接判定购买，转人工）；
 * CONFLICT：各传感器结论相互矛盾，转人工；
 * NO_CHARGE：订单本就未扣款，无需退款复核。
 */
public enum ReviewVerdict {
    CONFIRM_SUGGESTED,
    REFUND_SUGGESTED,
    CONFLICT,
    NO_CHARGE
}
