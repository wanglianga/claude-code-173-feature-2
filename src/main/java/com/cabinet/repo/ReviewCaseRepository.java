package com.cabinet.repo;

import com.cabinet.domain.ReviewCase;
import com.cabinet.domain.enums.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewCaseRepository extends JpaRepository<ReviewCase, Long> {
    Optional<ReviewCase> findByReviewNo(String reviewNo);
    Optional<ReviewCase> findBySessionId(Long sessionId);
    Optional<ReviewCase> findBySourceComplaintId(Long complaintId);
    List<ReviewCase> findByChannelIdOrderByIdDesc(Long channelId);
    List<ReviewCase> findByStatusOrderByIdDesc(ReviewStatus status);
    List<ReviewCase> findAllByOrderByIdDesc();
}
