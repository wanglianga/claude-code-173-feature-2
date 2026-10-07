package com.cabinet.domain.enums;

/**
 * 识别样本在误扣款复核后的标记（原始事件永不删除，只追加修正标记）：
 * VALID 有效；INVALIDATED 误识别样本（误扣款责任样本，回流模型纠偏）；REAFFIRMED 复核维持。
 */
public enum SampleStatus {
    VALID,
    INVALIDATED,
    REAFFIRMED
}
