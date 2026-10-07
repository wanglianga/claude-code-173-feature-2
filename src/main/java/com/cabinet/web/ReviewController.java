package com.cabinet.web;

import com.cabinet.domain.ReviewActionLog;
import com.cabinet.domain.ReviewCase;
import com.cabinet.dto.ReviewEvidenceView;
import com.cabinet.service.RefundService;
import com.cabinet.service.ReviewCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 误扣款复核：客服/运营把开门、拿取、重量变化、视觉识别、货道盘点、扣款时间聚合查看，
 * 系统初判后由人工给出维持扣款/退款/驳回结论；退款全程幂等。
 */
@Tag(name = "误扣款复核", description = "复核立案、证据聚合回放、维持扣款/退款/驳回、操作留痕")
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewCaseService reviewService;

    public record OpenRequest(String complaintNo, String sessionNo, String channelCode) {}
    public record RefundRequest(List<RefundService.RefundLine> refundItems,
                                Boolean goodsReturned, String conclusion) {}
    public record ConclusionRequest(String conclusion) {}

    @Operation(summary = "发起复核：传 complaintNo 从投诉立案，或传 sessionNo 对扣款会话直接立案")
    @PostMapping
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN')")
    public ReviewCase open(@RequestBody OpenRequest req, Principal principal) {
        if (req.complaintNo() != null && !req.complaintNo().isBlank()) {
            return reviewService.openFromComplaint(req.complaintNo(), req.channelCode(),
                    principal.getName());
        }
        return reviewService.openFromSession(req.sessionNo(), req.channelCode(),
                principal.getName());
    }

    @Operation(summary = "复核单列表（status: MANUAL_REVIEW/AUTO_PENDING/UPHELD/REFUNDED/REJECTED/ALL）")
    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN','FINANCE')")
    public List<ReviewCase> list(@RequestParam(defaultValue = "ALL") String status) {
        return reviewService.list(status);
    }

    @Operation(summary = "复核单详情")
    @GetMapping("/{reviewNo}")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN','FINANCE')")
    public ReviewCase get(@PathVariable String reviewNo) {
        return reviewService.get(reviewNo);
    }

    @Operation(summary = "证据聚合回放：开门/重量/各源识别/货道盘点/补传数据/扣款时间与系统初判")
    @GetMapping("/{reviewNo}/evidence")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN','FINANCE')")
    public ReviewEvidenceView evidence(@PathVariable String reviewNo) {
        return reviewService.evidence(reviewNo);
    }

    @Operation(summary = "证据充分，维持原扣款（向用户展示扣款依据，样本标记有效）")
    @PostMapping("/{reviewNo}/uphold")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN')")
    public ReviewCase uphold(@PathVariable String reviewNo, @RequestBody ConclusionRequest req,
                             Principal principal) {
        return reviewService.uphold(reviewNo, principal.getName(), req.conclusion());
    }

    @Operation(summary = "确认误判并退款（整单/按行，商品是否收回）；重复点击不会二次退款")
    @PostMapping("/{reviewNo}/refund")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN')")
    public ReviewCase refund(@PathVariable String reviewNo, @RequestBody RefundRequest req,
                             Principal principal) {
        return reviewService.grantRefund(reviewNo, principal.getName(), req.refundItems(),
                Boolean.TRUE.equals(req.goodsReturned()), req.conclusion());
    }

    @Operation(summary = "驳回申诉（不支持退款）")
    @PostMapping("/{reviewNo}/reject")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN')")
    public ReviewCase reject(@PathVariable String reviewNo, @RequestBody ConclusionRequest req,
                             Principal principal) {
        return reviewService.reject(reviewNo, principal.getName(), req.conclusion());
    }

    @Operation(summary = "补传/盘点新证据后重开复核（已退款单不可重开）")
    @PostMapping("/{reviewNo}/reopen")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public ReviewCase reopen(@PathVariable String reviewNo, @RequestBody ConclusionRequest req,
                             Principal principal) {
        return reviewService.reopen(reviewNo, principal.getName(), req.conclusion());
    }

    @Operation(summary = "扣款依据（维持扣款后向用户展示：开门/识别源/称重/扣款时间）")
    @GetMapping("/{reviewNo}/charge-basis")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> chargeBasis(@PathVariable String reviewNo) {
        return reviewService.chargeBasis(reviewNo);
    }

    @Operation(summary = "复核操作时间线（系统初判 + 每次人工修正 + 补传/换机记录）")
    @GetMapping("/{reviewNo}/timeline")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN','FINANCE')")
    public List<ReviewActionLog> timeline(@PathVariable String reviewNo) {
        return reviewService.timeline(reviewNo);
    }

    @Operation(summary = "登记设备换机（原始证据保留在旧柜机）")
    @PostMapping("/{reviewNo}/cabinet-replaced")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public ReviewCase replaced(@PathVariable String reviewNo,
                               @RequestBody Map<String, String> req, Principal principal) {
        return reviewService.noteCabinetReplaced(reviewNo, req.get("newCabinetNo"),
                principal.getName());
    }
}
