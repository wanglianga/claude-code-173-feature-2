package com.cabinet.service;

import com.cabinet.domain.ReviewAudit;
import com.cabinet.repo.ReviewAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 复核审计：误扣款复核全流程留痕。
 * 原始系统研判、人工判责、重复申诉、设备补传、货道暂停/换机、
 * 已结算订单修正等只追加不覆盖，保证原始判断与后续修正过程均可追溯。
 */
@Service
@RequiredArgsConstructor
public class ReviewAuditService {

    private final ReviewAuditRepository auditRepository;

    @Transactional(propagation = Propagation.REQUIRED)
    public ReviewAudit log(String refType, String refNo, String action, String actor, String detail) {
        return auditRepository.save(ReviewAudit.builder()
                .refType(refType).refNo(refNo)
                .action(action)
                .actor(actor == null ? "system" : actor)
                .detail(detail)
                .build());
    }

    @Transactional(readOnly = true)
    public List<ReviewAudit> trail(String refType, String refNo) {
        return auditRepository.findByRefTypeAndRefNoOrderByIdAsc(refType, refNo);
    }
}
