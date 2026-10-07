package com.cabinet.domain.enums;

/** 复核系统研判建议（仅建议，最终由人工判责） */
public enum ReviewSuggestion {
    /** 证据充分证明用户已取走：建议驳回并向用户展示扣款依据 */
    EVIDENCE_SUFFICIENT,
    /** 重量已恢复但视觉仍认为取走等疑似误扣：不得直接判定购买，转人工确认 */
    SUSPECT_MISCHARGE,
    /** 证据相互冲突，转人工确认 */
    NEED_MANUAL
}
