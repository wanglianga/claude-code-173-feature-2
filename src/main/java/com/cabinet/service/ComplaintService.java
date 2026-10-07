package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.dto.EvidenceView;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 客服投诉与售后：
 * - 重复申诉防护：同一会话已有投诉时，新工单标记 DUPLICATE 并关联首单，不重复立案/退款；
 * - 证据回放：开门记录、多源识别结果（含视频片段定位）、订单明细、批次库存变化、温控曲线；
 * - 判责退款统一走 {@link RefundService}（订单行锁 + 退款幂等键 + 账户流水幂等），
 *   商品已收回回补库存（REFUND_RETURN），未收回登记报损 REFUND_NO_RETURN。
 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    private final ComplaintRepository complaintRepository;
    private final DoorSessionRepository sessionRepository;
    private final RecognitionEventRepository eventRepository;
    private final OrderRecordRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final BatchLedgerRepository ledgerRepository;
    private final TempLogRepository tempLogRepository;
    private final AppUserRepository userRepository;
    private final RefundService refundService;
    private final AccountService accountService;

    @Transactional
    public Complaint submit(String sessionNo, String category, String description) {
        DoorSession session = sessionRepository.findBySessionNo(sessionNo)
                .orElseThrow(() -> ApiException.notFound("开门会话不存在"));
        OrderRecord order = orderRepository.findBySessionId(session.getId()).orElse(null);
        accountService.ensureAccount(session.getUserCode(), session.getUserName());

        List<Complaint> prior = complaintRepository.findBySessionIdOrderByIdAsc(session.getId());
        Complaint first = prior.stream().filter(c -> !c.isDuplicate()).findFirst()
                .orElse(prior.isEmpty() ? null : prior.get(0));

        Complaint complaint = Complaint.builder()
                .complaintNo(genNo("CP"))
                .session(session).order(order)
                .userCode(session.getUserCode()).userName(session.getUserName())
                .category(category).description(description)
                .status(ComplaintStatus.SUBMITTED)
                .build();

        if (first != null) {
            // 重复申诉：保留工单但不进入新的退款流程
            complaint.setDuplicate(true);
            complaint.setDuplicateOfId(first.getId());
            complaint.setDuplicateCount(first.getDuplicateCount() + 1);
            complaint.setStatus(ComplaintStatus.DUPLICATE);
            complaint.setResolution("重复申诉，关联首条工单 " + first.getComplaintNo()
                    + "（当前结论 " + first.getStatus() + "），按原结论执行，不二次退款");
            complaint.setResolvedAt(LocalDateTime.now());
            return complaintRepository.save(complaint);
        }
        return complaintRepository.save(complaint);
    }

    /** 组装售后证据回放视图 */
    @Transactional(readOnly = true)
    public EvidenceView evidence(String complaintNo) {
        Complaint c = complaintRepository.findByComplaintNo(complaintNo)
                .orElseThrow(() -> ApiException.notFound("投诉单不存在"));
        DoorSession session = c.getSession();
        List<RecognitionEvent> events =
                eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId());
        OrderRecord order = c.getOrder();
        List<OrderItem> items = order != null
                ? orderItemRepository.findByOrderId(order.getId()) : List.of();

        List<BatchLedger> ledgers = new ArrayList<>();
        for (OrderItem oi : items) {
            if (oi.getBatch() != null) {
                ledgers.addAll(ledgerRepository.findByBatchIdOrderByIdAsc(oi.getBatch().getId()));
            }
        }
        ledgers.sort(java.util.Comparator.comparing(BatchLedger::getId));

        List<TempLog> temps = tempLogRepository
                .findTop200ByCabinetIdOrderByRecordedAtDesc(session.getCabinet().getId());

        return new EvidenceView(c, session, order, items, events, ledgers, temps);
    }

    /**
     * 判责处理（保留旧入口）：退款走统一幂等执行器。
     * refundItems: orderItemId -> 退款数量（为空且 grantRefund=true 则整单退）
     * goodsReturned: 商品是否已收回；未收回 -> 报损
     */
    @Transactional
    public Complaint resolve(String complaintNo, String handlerUsername, boolean grantRefund,
                             List<RefundLine> refundItems, boolean goodsReturned,
                             String resolution) {
        Complaint complaint = complaintRepository.findByComplaintNo(complaintNo)
                .orElseThrow(() -> ApiException.notFound("投诉单不存在"));
        if (complaint.isDuplicate()) {
            throw ApiException.conflict("重复申诉工单不可直接退款，已关联首条工单 #"
                    + complaint.getDuplicateOfId());
        }
        if (complaint.getStatus() == ComplaintStatus.RESOLVED_REFUNDED
                || complaint.getStatus() == ComplaintStatus.RESOLVED_REJECTED
                || complaint.getStatus() == ComplaintStatus.RESOLVED_UPHELD) {
            throw ApiException.conflict("投诉已结案（" + complaint.getStatus()
                    + "），重复提交不会二次退款");
        }
        AppUser handler = userRepository.findByUsername(handlerUsername)
                .orElseThrow(() -> ApiException.badRequest("客服账号不存在"));
        complaint.setHandler(handler);
        complaint.setResolution(resolution);
        complaint.setResolvedAt(LocalDateTime.now());

        BigDecimal refundTotal = BigDecimal.ZERO;
        String refundDetail = "";

        if (grantRefund && complaint.getOrder() != null) {
            List<RefundService.RefundLine> lines = refundItems == null ? null
                    : refundItems.stream()
                        .map(r -> new RefundService.RefundLine(r.orderItemId(), r.qty())).toList();
            RefundService.RefundResult result = refundService.execute(
                    complaint.getOrder(), complaint, null,
                    "COMPLAINT:" + complaint.getId(), lines, goodsReturned,
                    "投诉 " + complaintNo + " " + resolution);
            refundTotal = result.amount();
            refundDetail = result.refundRecord().getDetailJson();
            complaint.setStatus(ComplaintStatus.RESOLVED_REFUNDED);
        } else {
            complaint.setStatus(ComplaintStatus.RESOLVED_REJECTED);
        }

        complaint.setRefundAmount(refundTotal);
        complaint.setRefundDetailJson(refundDetail);
        return complaintRepository.save(complaint);
    }

    private String genNo(String prefix) {
        return com.cabinet.util.BizNos.next(prefix);
    }

    public record RefundLine(Long orderItemId, Integer qty) {}
}
