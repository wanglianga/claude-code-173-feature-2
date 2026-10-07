package com.cabinet.domain.enums;

/** 识别样本判定：复核退款/驳回时同步修正识别样本，供识别模型纠偏 */
public enum SampleVerdict {
    NONE,       // 未判定
    CONFIRMED,  // 人工确认识别正确（证据充分，驳回投诉）
    MISJUDGED   // 误识别（复核退款认定）
}
