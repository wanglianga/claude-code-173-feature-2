package com.cabinet.repo;

import com.cabinet.domain.SampleCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SampleCorrectionRepository extends JpaRepository<SampleCorrection, Long> {
    Optional<SampleCorrection> findByRecognitionEventId(Long recognitionEventId);
    List<SampleCorrection> findByReviewCaseId(Long reviewCaseId);
}
