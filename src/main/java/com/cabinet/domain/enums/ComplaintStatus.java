package com.cabinet.domain.enums;

public enum ComplaintStatus {
    SUBMITTED,
    INVESTIGATING,
    RESOLVED_REFUNDED,
    RESOLVED_REJECTED,
    RESOLVED_UPHELD,    // 复核后维持原扣款，已向用户展示扣款依据
    DUPLICATE           // 重复申诉：关联首单，不再重复处理/退款
}
