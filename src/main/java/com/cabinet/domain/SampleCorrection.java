package com.cabinet.domain;

import com.cabinet.domain.enums.SampleStatus;
import jakarta.persistence.*;
import lombok.*;

/**
 * 识别样本纠偏标记：复核退款后，把导致误判的识别事件标记为 INVALIDATED（误识别样本），
 * 供模型回流纠偏；维持扣款则标记 REAFFIRMED。原始 recognition_event 永不删除/改写，
 * 修正只追加在本表，保留原始判断与后续修正过程。
 */
@Entity
@Table(name = "sample_correction",
        uniqueConstraints = @UniqueConstraint(columnNames = "recognition_event_id"),
        indexes = @Index(columnList = "status"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SampleCorrection extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recognition_event_id")
    private RecognitionEvent recognitionEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    private ReviewCase reviewCase;

    @Enumerated(EnumType.STRING)
    private SampleStatus status;

    @Column(length = 500)
    private String reason;
}
