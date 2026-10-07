package com.cabinet.repo;

import com.cabinet.domain.RecognitionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecognitionEventRepository extends JpaRepository<RecognitionEvent, Long> {
    List<RecognitionEvent> findBySessionIdOrderByDetectedAtAsc(Long sessionId);
    List<RecognitionEvent> findBySessionIdAndLateArrivalTrue(Long sessionId);
}
