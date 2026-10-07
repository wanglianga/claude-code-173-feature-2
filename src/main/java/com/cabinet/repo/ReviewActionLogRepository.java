package com.cabinet.repo;

import com.cabinet.domain.ReviewActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewActionLogRepository extends JpaRepository<ReviewActionLog, Long> {
    List<ReviewActionLog> findByReviewCaseIdOrderByIdAsc(Long reviewCaseId);
}
