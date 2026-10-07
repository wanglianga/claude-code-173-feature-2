package com.cabinet.repo;

import com.cabinet.domain.RecognitionEvent;
import com.cabinet.domain.enums.SampleVerdict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecognitionEventRepository extends JpaRepository<RecognitionEvent, Long> {
    List<RecognitionEvent> findBySessionIdOrderByDetectedAtAsc(Long sessionId);

    /** 误识别样本（复核退款时同步修正），供识别模型纠偏 */
    List<RecognitionEvent> findByVerdictOrderByIdDesc(SampleVerdict verdict);
}
