package com.cabinet.web;

import com.cabinet.domain.Complaint;
import com.cabinet.domain.RecognitionEvent;
import com.cabinet.domain.ReviewAudit;
import com.cabinet.domain.enums.ComplaintStatus;
import com.cabinet.domain.enums.SampleVerdict;
import com.cabinet.dto.ChargeBasisView;
import com.cabinet.dto.ReviewEvidenceView;
import com.cabinet.repo.ComplaintRepository;
import com.cabinet.repo.RecognitionEventRepository;
import com.cabinet.service.ComplaintService;
import com.cabinet.service.ReviewAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * 误扣款复核工作台：证据聚合（开门/拿取/重量/视觉/货道盘点/扣款时间）、
 * 系统研判建议、幂等判责、审计轨迹、用户侧扣款依据、误识别样本。
 */
@Tag(name = "误扣款复核",
     description = "复核证据聚合视图、系统研判、幂等判责退款、审计轨迹、扣款依据展示、误识别样本")
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ComplaintService complaintService;
    private final ComplaintRepository complaintRepository;
    private final RecognitionEventRepository eventRepository;
    private final ReviewAuditService auditService;

    @Operation(summary = "待复核队列（含系统研判建议快照）")
    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN')")
    public List<Complaint> queue() {
        return complaintRepository.findByStatusIn(
                List.of(ComplaintStatus.SUBMITTED, ComplaintStatus.INVESTIGATING));
    }

    @Operation(summary = "复核证据聚合：开门+拿取+重量变化+视觉识别+货道盘点+扣款时间+系统研判")
    @GetMapping("/{complaintNo}/evidence")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN','FINANCE')")
    public ReviewEvidenceView evidence(@PathVariable String complaintNo) {
        return complaintService.reviewEvidence(complaintNo);
    }

    public record ResolveRequest(Boolean grantRefund,
                                 List<ComplaintService.RefundLine> refundItems,
                                 Boolean goodsReturned,
                                 String resolution) {}

    @Operation(summary = "判责（幂等）：退款则同步修正订单/余额/库存/识别样本；重复提交不二次退款")
    @PostMapping("/{complaintNo}/resolve")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','ADMIN')")
    public Complaint resolve(@PathVariable String complaintNo, @RequestBody ResolveRequest req,
                             Principal principal) {
        return complaintService.resolve(complaintNo, principal.getName(),
                Boolean.TRUE.equals(req.grantRefund()),
                req.refundItems(),
                Boolean.TRUE.equals(req.goodsReturned()),
                req.resolution());
    }

    @Operation(summary = "投诉单审计轨迹（原始研判、判责、重复申诉、补传、货道处置全程留痕）")
    @GetMapping("/{complaintNo}/audits")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN')")
    public List<ReviewAudit> audits(@PathVariable String complaintNo) {
        return auditService.trail("COMPLAINT", complaintNo);
    }

    @Operation(summary = "用户侧扣款依据：证据充分时向用户展示扣款原因与视频片段")
    @GetMapping("/charge-basis/{orderNo}")
    @PreAuthorize("isAuthenticated()")
    public ChargeBasisView chargeBasis(@PathVariable String orderNo) {
        return complaintService.chargeBasis(orderNo);
    }

    @Operation(summary = "误识别样本列表（复核退款时同步修正，供识别模型纠偏）")
    @GetMapping("/samples/misjudged")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','CUSTOMER_SERVICE')")
    public List<RecognitionEvent> misjudgedSamples() {
        return eventRepository.findByVerdictOrderByIdDesc(SampleVerdict.MISJUDGED);
    }
}
