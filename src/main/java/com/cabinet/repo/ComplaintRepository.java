package com.cabinet.repo;

import com.cabinet.domain.Complaint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
    Optional<Complaint> findByComplaintNo(String complaintNo);
    List<Complaint> findBySessionIdOrderByIdAsc(Long sessionId);
}
