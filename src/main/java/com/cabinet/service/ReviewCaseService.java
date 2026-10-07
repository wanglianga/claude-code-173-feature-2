package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.dto.ReviewEvidenceView;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 误扣款复核主流程：
 * 1) 立案：聚合同一次开门的开门时间、拿取、重量变化、视觉/RFID 识别、货道盘点、扣款时间，
 *    系统给出初判；重量恢复但视觉仍判取走 / 多源冲突 -> 一律转人工，不自动出结论；
 * 2) 证据充分：客服维持扣款（UPHELD），向用户展示扣款依据，识别样本标记 REAFFIRMED；
 * 3) 确认误判：执行幂等退款，同步修正订单状态/账户余额/货道库存/批次台账/识别样本(INVALIDATED)，
 *    并登记货道误判次数（反复出现自动暂停货道安排维护）；
 * 4) 已结算订单：允许退款，记录跨期结算修正；重复申诉、换机、离线补传均保留原始判断与修正过程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewCaseService {

    private final ReviewCaseRepository reviewRepository;
    private final ReviewActionLogRepository actionLogRepository;
    private final ComplaintRepository complaintRepository;
    private final DoorSessionRepository sessionRepository;
    private final OrderRecordRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final RecognitionEventRepository eventRepository;
    private final StocktakeOrderRepository stocktakeRepository;
    private final StocktakeItemRepository stocktakeItemRepository;
    private final ChannelRepository channelRepository;
    private final AppUserRepository userRepository;
    private final SampleCorrectionRepository sampleRepository;
    private final RefundService refundService;
    private final ChannelHealthService healthService;
    private final AlertService alertService;
    private final EvidenceAssessor assessor;
    private final ObjectMapper objectMapper;

    @Value("${cabinet.recognition.weight-tolerance-gram:80}")
    private int weightToleranceGram;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /* ============================ 立案与证据 ============================ */

    /** 从投诉单发起复核（重复申诉直接拒绝，不再重复立案） */
    @Transactional
    public ReviewCase openFromComplaint(String complaintNo, String focusChannelCode,
                                        String handlerUsername) {
        Complaint complaint = complaintRepository.findByComplaintNo(complaintNo)
                .orElseThrow(() -> ApiException.notFound("投诉单不存在"));
        if (complaint.isDuplicate()) {
            throw ApiException.conflict("该申诉为重复申诉，已关联首条工单 #"
                    + complaint.getDuplicateOfId() + "，按原复核结论处理，不重复退款");
        }
        ReviewCase existing = reviewRepository.findBySessionId(complaint.getSession().getId())
                .orElse(null);
        if (existing != null) {
            if (existing.getStatus() == ReviewStatus.AUTO_PENDING
                    || existing.getStatus() == ReviewStatus.MANUAL_REVIEW) {
                return existing; // 立案接口幂等：未结案直接返回
            }
            throw ApiException.conflict("该会话复核已结案（" + existing.getStatus()
                    + "，复核单 " + existing.getReviewNo() + "），重复申诉不二次处理");
        }
        complaint.setStatus(ComplaintStatus.INVESTIGATING);
        complaintRepository.save(complaint);
        return createCase(complaint.getSession(), complaint, focusChannelCode, handlerUsername);
    }

    /** 运营对扣款会话直接发起复核（无需投诉单） */
    @Transactional
    public ReviewCase openFromSession(String sessionNo, String focusChannelCode,
                                      String handlerUsername) {
        DoorSession session = sessionRepository.findBySessionNo(sessionNo)
                .orElseThrow(() -> ApiException.notFound("开门会话不存在"));
        ReviewCase existing = reviewRepository.findBySessionId(session.getId()).orElse(null);
        if (existing != null
                && (existing.getStatus() == ReviewStatus.AUTO_PENDING
                    || existing.getStatus() == ReviewStatus.MANUAL_REVIEW)) {
            return existing;
        }
        if (existing != null) {
            throw ApiException.conflict("该会话复核已结案：" + existing.getReviewNo());
        }
        return createCase(session, null, focusChannelCode, handlerUsername);
    }

    private ReviewCase createCase(DoorSession session, Complaint complaint,
                                  String focusChannelCode, String handlerUsername) {
        OrderRecord order = orderRepository.findBySessionId(session.getId()).orElse(null);
        if (order != null && order.getStatus() == OrderStatus.REFUNDED) {
            throw ApiException.conflict("该订单已全额退款，资金已回补，不能重复发起退款复核"
                    + "（如有新争议请新建工单并关联原单）");
        }
        List<RecognitionEvent> events =
                eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId());
        List<OrderItem> items = order != null
                ? orderItemRepository.findByOrderId(order.getId()) : List.of();
        boolean surplus = detectStocktakeSurplus(session, events, focusChannelCode);

        EvidenceAssessor.Assessment assessment = assessor.assess(
                session, order, items, events, weightToleranceGram, surplus);

        // 未扣款订单无需资金复核
        if (assessment.verdict() == ReviewVerdict.NO_CHARGE) {
            throw ApiException.conflict("该会话订单未实际扣款，无需误扣款复核（可直接驳回投诉）");
        }

        String channelCode = focusChannelCode != null ? focusChannelCode
                : items.stream().map(OrderItem::getChannelCode).findFirst().orElse(null);
        Channel channel = channelCode == null ? null
                : channelRepository.findByCabinetIdAndCode(session.getCabinet().getId(), channelCode)
                        .orElse(null);

        ReviewStatus status = assessment.verdict() == ReviewVerdict.CONFIRM_SUGGESTED
                ? ReviewStatus.AUTO_PENDING : ReviewStatus.MANUAL_REVIEW;

        ReviewCase reviewCase = ReviewCase.builder()
                .reviewNo(genNo("RV"))
                .session(session).cabinet(session.getCabinet()).channel(channel)
                .order(order).complaint(complaint)
                .userCode(session.getUserCode()).userName(session.getUserName())
                .status(status)
                .initialVerdict(assessment.verdict())
                .initialReason(String.join("；", assessment.reasons()))
                .openedAt(session.getOpenedAt())
                .chargedAt(order != null ? order.getChargedAt() : null)
                .sourceComplaintId(complaint != null ? complaint.getId() : null)
                .build();
        reviewCase.setEvidenceSnapshotJson(snapshot(session, order, items, events,
                assessment, channelCode));
        reviewCase = reviewRepository.save(reviewCase);

        logAction(reviewCase, "system", "SYSTEM_INITIAL",
                "系统初判 " + assessment.verdict() + "：" + String.join("；", assessment.reasons()));
        if (complaint != null) {
            logAction(reviewCase, handlerUsername == null ? "system" : handlerUsername,
                    "SUBMIT", "依据投诉 " + complaint.getComplaintNo()
                            + " 立案：" + complaint.getDescription());
        }

        if (status == ReviewStatus.MANUAL_REVIEW) {
            alertService.raise(session.getCabinet(), AlertType.MISCHARGE_REVIEW, "WARN",
                    "会话 " + session.getSessionNo() + " 初判 " + assessment.verdict()
                            + "，须人工复核（不得直接判定购买）：" + reviewCase.getReviewNo(),
                    "REVIEW", reviewCase.getId());
        }
        return reviewCase;
    }

    /** 组装复核证据聚合视图 */
    @Transactional(readOnly = true)
    public ReviewEvidenceView evidence(String reviewNo) {
        ReviewCase rc = must(reviewNo);
        DoorSession session = rc.getSession();
        OrderRecord order = rc.getOrder();
        List<RecognitionEvent> events =
                eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId());
        List<OrderItem> items = order != null
                ? orderItemRepository.findByOrderId(order.getId()) : List.of();
        boolean surplus = detectStocktakeSurplus(session, events,
                rc.getChannel() != null ? rc.getChannel().getCode() : null);
        EvidenceAssessor.Assessment a = assessor.assess(
                session, order, items, events, weightToleranceGram, surplus);

        String focus = rc.getChannel() != null ? rc.getChannel().getCode() : null;
        List<ReviewEvidenceView.SourceNetView> nets = a.sourceCounts().stream()
                .map(c -> new ReviewEvidenceView.SourceNetView(
                        c.source(), c.channelCode(), c.sku(),
                        c.pick(), c.put(), c.net(), c.minConfidence()))
                .toList();

        return new ReviewEvidenceView(
                session.getSessionNo(),
                session.getCabinet().getCabinetNo(),
                focus,
                fmt(session.getOpenedAt()), fmt(session.getClosedAt()),
                order != null && order.getChargedAt() != null ? fmt(order.getChargedAt()) : null,
                doorToCharge(session, order),
                session.getWeightDeltaGram(), a.weightRestored(),
                order != null ? order.getStatus().name() : null,
                nets, a.reasons(),
                stocktakeViews(session, focus),
                lateViews(events),
                rc.getFinalVerdict() != null ? rc.getFinalVerdict().name()
                        : rc.getInitialVerdict().name(),
                rc.getConclusion() != null ? rc.getConclusion() : rc.getInitialReason());
    }

    /* ============================ 人工结论 ============================ */

    /** 证据充分，维持扣款：向用户展示扣款依据 */
    @Transactional
    public ReviewCase uphold(String reviewNo, String handlerUsername, String conclusion) {
        ReviewCase rc = lockCase(reviewNo);
        ensureOpen(rc);
        AppUser handler = mustHandler(handlerUsername);
        rc.setHandler(handler);
        rc.setStatus(ReviewStatus.UPHELD);
        rc.setFinalVerdict(ReviewVerdict.CONFIRM_SUGGESTED);
        rc.setConclusion("维持原扣款。" + conclusion);
        rc.setReviewedAt(LocalDateTime.now());
        reviewRepository.save(rc);
        markSamples(rc, SampleStatus.REAFFIRMED, "复核维持扣款，样本有效");
        if (rc.getComplaint() != null) {
            Complaint c = rc.getComplaint();
            c.setStatus(ComplaintStatus.RESOLVED_UPHELD);
            c.setResolution("证据充分维持扣款：" + conclusion);
            c.setResolvedAt(LocalDateTime.now());
            c.setHandler(handler);
            complaintRepository.save(c);
        }
        logAction(rc, handlerUsername, "UPHOLD", "维持扣款并向用户展示依据：" + conclusion);
        return rc;
    }

    /** 驳回申诉（不支持退款） */
    @Transactional
    public ReviewCase reject(String reviewNo, String handlerUsername, String conclusion) {
        ReviewCase rc = lockCase(reviewNo);
        ensureOpen(rc);
        AppUser handler = mustHandler(handlerUsername);
        rc.setHandler(handler);
        rc.setStatus(ReviewStatus.REJECTED);
        rc.setFinalVerdict(ReviewVerdict.CONFIRM_SUGGESTED);
        rc.setConclusion("驳回退款：" + conclusion);
        rc.setReviewedAt(LocalDateTime.now());
        reviewRepository.save(rc);
        if (rc.getComplaint() != null) {
            Complaint c = rc.getComplaint();
            c.setStatus(ComplaintStatus.RESOLVED_REJECTED);
            c.setResolution(conclusion);
            c.setResolvedAt(LocalDateTime.now());
            c.setHandler(handler);
            complaintRepository.save(c);
        }
        logAction(rc, handlerUsername, "REJECT", "驳回：" + conclusion);
        return rc;
    }

    /**
     * 确认误判并退款（幂等：结案后重复点击/并发/补传重试不会二次退款）。
     * @param lines 指定行退款；null/空整单退
     */
    @Transactional
    public ReviewCase grantRefund(String reviewNo, String handlerUsername,
                                  List<RefundService.RefundLine> lines,
                                  boolean goodsReturned, String conclusion) {
        ReviewCase rc = lockCase(reviewNo);
        if (rc.getStatus() == ReviewStatus.REFUNDED) {
            return rc; // 重复点击安全：直接返回已退款结案单
        }
        ensureOpen(rc);
        AppUser handler = mustHandler(handlerUsername);

        RefundService.RefundResult result = refundService.execute(
                rc.getOrder(), rc.getComplaint(), rc,
                "REVIEW:" + rc.getId(), lines, goodsReturned,
                "复核单 " + rc.getReviewNo() + " " + conclusion);

        // 并发/重复提交：另一事务已完成退款，直接返回结案单，不重复修正库存/样本/货道健康度
        if (result.deduplicated()) {
            return reviewRepository.findById(rc.getId()).orElse(rc);
        }

        rc.setHandler(handler);
        rc.setStatus(ReviewStatus.REFUNDED);
        rc.setFinalVerdict(ReviewVerdict.REFUND_SUGGESTED);
        rc.setGoodsReturned(goodsReturned);
        rc.setRefundAmount(result.amount());
        rc.setRefundNo(result.refundRecord().getRefundNo());
        rc.setConclusion("确认误判已退款。" + conclusion
                + (result.settlementImpact() != null ? "；" + result.settlementImpact() : ""));
        rc.setReviewedAt(LocalDateTime.now());
        reviewRepository.save(rc);

        // 识别样本纠偏：导致误判的原始识别事件标记为误识别样本（不删除原事件）
        markSamples(rc, SampleStatus.INVALIDATED, "误扣款退款，识别样本判误：" + conclusion);

        if (rc.getComplaint() != null) {
            Complaint c = rc.getComplaint();
            c.setStatus(ComplaintStatus.RESOLVED_REFUNDED);
            c.setHandler(handler);
            c.setRefundAmount(result.amount());
            c.setResolution("复核确认误判已退款：" + conclusion);
            c.setResolvedAt(LocalDateTime.now());
            complaintRepository.save(c);
        }

        // 货道健康度：登记误判，反复出现自动暂停货道
        if (rc.getChannel() != null) {
            healthService.registerMiscount(rc.getChannel(), rc.getReviewNo(), conclusion);
        }

        logAction(rc, handlerUsername, "REFUND",
                "确认误判退款 " + result.amount() + " 元，商品"
                        + (goodsReturned ? "已收回回补库存" : "未收回登记报损")
                        + (result.settlementImpact() != null ? "；" + result.settlementImpact() : "")
                        + "；" + conclusion);
        return rc;
    }

    /** 离线补传数据到达：固化“后续修正过程”，不改写原始初判快照 */
    @Transactional
    public ReviewCase noteLateData(String reviewNo, String note) {
        ReviewCase rc = must(reviewNo);
        rc.setLateDataArrived(true);
        rc.setLateDataNote(note);
        reviewRepository.save(rc);
        logAction(rc, "system", "LATE_DATA",
                "设备离线后补传数据到达，原始初判与证据快照保持不变：" + note);
        alertService.raise(rc.getCabinet(), AlertType.LATE_DEVICE_DATA, "INFO",
                "复核单 " + rc.getReviewNo() + " 关联会话收到离线补传数据", "REVIEW", rc.getId());
        return rc;
    }

    /** 换机时把新柜机编号登记到在途/历史复核单，原始证据仍归属旧柜机 */
    @Transactional
    public ReviewCase noteCabinetReplaced(String reviewNo, String newCabinetNo, String operator) {
        ReviewCase rc = must(reviewNo);
        rc.setReplacedCabinetNo(newCabinetNo);
        reviewRepository.save(rc);
        logAction(rc, operator, "CABINET_REPLACED",
                "货道换机至 " + newCabinetNo + "，原始开门/识别/盘点证据保留在原柜机");
        return rc;
    }

    /** 已结案复核在补传新证据后由运营重开（只允许一次，且全程留痕） */
    @Transactional
    public ReviewCase reopen(String reviewNo, String operator, String reason) {
        ReviewCase rc = lockCase(reviewNo);
        if (rc.getStatus() == ReviewStatus.AUTO_PENDING
                || rc.getStatus() == ReviewStatus.MANUAL_REVIEW) {
            return rc;
        }
        if (rc.getStatus() == ReviewStatus.REFUNDED) {
            throw ApiException.conflict("已退款结案的复核单不可重开（资金已回补，如有新争议请新建工单并关联本单）");
        }
        rc.setStatus(ReviewStatus.MANUAL_REVIEW);
        reviewRepository.save(rc);
        logAction(rc, operator, "REOPEN", "补传/盘点新证据，重开人工复核：" + reason);
        return rc;
    }

    @Transactional(readOnly = true)
    public List<ReviewActionLog> timeline(String reviewNo) {
        ReviewCase rc = must(reviewNo);
        return actionLogRepository.findByReviewCaseIdOrderByIdAsc(rc.getId());
    }

    @Transactional(readOnly = true)
    public ReviewCase get(String reviewNo) {
        return must(reviewNo);
    }

    /**
     * 面向用户的扣款依据（维持扣款后可向用户展示）：
     * 仅在证据充分并维持扣款时提供，内容为各源一致的净拿取、称重匹配与扣款明细摘要。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> chargeBasis(String reviewNo) {
        ReviewCase rc = must(reviewNo);
        if (rc.getStatus() != ReviewStatus.UPHELD) {
            throw ApiException.conflict("仅在复核维持扣款后可展示扣款依据（当前状态 "
                    + rc.getStatus() + "）");
        }
        ReviewEvidenceView ev = evidence(reviewNo);
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("reviewNo", rc.getReviewNo());
        basis.put("sessionNo", ev.sessionNo());
        basis.put("cabinetNo", ev.cabinetNo());
        basis.put("openedAt", ev.openedAt());
        basis.put("chargedAt", ev.chargedAt());
        basis.put("doorToChargeMillis", ev.doorToChargeMillis());
        basis.put("weightDeltaGram", ev.weightDeltaGram());
        basis.put("sourceNets", ev.sourceNets());
        basis.put("conclusion", rc.getConclusion());
        return basis;
    }

    @Transactional(readOnly = true)
    public List<ReviewCase> list(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return reviewRepository.findAllByOrderByIdDesc();
        }
        return reviewRepository.findByStatusOrderByIdDesc(ReviewStatus.valueOf(status));
    }

    /* ============================ 私有辅助 ============================ */

    private void markSamples(ReviewCase rc, SampleStatus status, String reason) {
        List<RecognitionEvent> events =
                eventRepository.findBySessionIdOrderByDetectedAtAsc(rc.getSession().getId());
        String focus = rc.getChannel() != null ? rc.getChannel().getCode() : null;
        for (RecognitionEvent e : events) {
            if (focus != null && !focus.equals(e.getChannelCode())) continue;
            if (e.getSource() == RecognitionSource.MANUAL) continue;
            SampleCorrection existing =
                    sampleRepository.findByRecognitionEventId(e.getId()).orElse(null);
            if (existing != null) {
                existing.setStatus(status);
                existing.setReason(reason);
                sampleRepository.save(existing);
            } else {
                sampleRepository.save(SampleCorrection.builder()
                        .recognitionEvent(e).reviewCase(rc)
                        .status(status).reason(reason).build());
            }
        }
    }

    /** 盘点盘盈反证：该柜机盘点单中，相关货道实盘 > 系统数 */
    private boolean detectStocktakeSurplus(DoorSession session, List<RecognitionEvent> events,
                                           String focusChannel) {
        Set<String> channels = new HashSet<>();
        if (focusChannel != null) channels.add(focusChannel);
        events.stream().map(RecognitionEvent::getChannelCode).filter(Objects::nonNull)
                .forEach(channels::add);
        for (StocktakeOrder so : stocktakeRepository.findByCabinetIdOrderByStartedAtDesc(
                session.getCabinet().getId())) {
            for (StocktakeItem si : stocktakeItemRepository.findByStocktakeId(so.getId())) {
                if (!channels.contains(si.getChannel().getCode())) continue;
                if (si.getCountedQty() != null && si.getSystemQty() != null
                        && si.getCountedQty() > si.getSystemQty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<ReviewEvidenceView.StocktakeLineView> stocktakeViews(DoorSession session,
                                                                      String focus) {
        List<ReviewEvidenceView.StocktakeLineView> views = new ArrayList<>();
        for (StocktakeOrder so : stocktakeRepository.findByCabinetIdOrderByStartedAtDesc(
                session.getCabinet().getId())) {
            for (StocktakeItem si : stocktakeItemRepository.findByStocktakeId(so.getId())) {
                if (focus != null && !focus.equals(si.getChannel().getCode())) continue;
                views.add(new ReviewEvidenceView.StocktakeLineView(
                        so.getOrderNo(), so.getStatus().name(),
                        si.getChannel().getCode(),
                        si.getBatch() != null ? si.getBatch().getBatchNo() : null,
                        si.getSystemQty(), si.getCountedQty(), si.getDiffQty(),
                        fmt(so.getStartedAt())));
            }
        }
        return views;
    }

    private List<ReviewEvidenceView.LateEventView> lateViews(List<RecognitionEvent> events) {
        return events.stream().filter(RecognitionEvent::isLateArrival).map(e ->
                new ReviewEvidenceView.LateEventView(
                        e.getSource().name(), e.getAction().name(), e.getChannelCode(),
                        e.getProduct() != null ? e.getProduct().getSku() : null,
                        e.getQty(), fmt(e.getDetectedAt()),
                        "关门结算后补传，仅作复核参考，不改变原始结算")).toList();
    }

    private String snapshot(DoorSession session, OrderRecord order, List<OrderItem> items,
                            List<RecognitionEvent> events, EvidenceAssessor.Assessment a,
                            String channelCode) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("snapshotAt", LocalDateTime.now().format(FMT));
            snap.put("sessionNo", session.getSessionNo());
            snap.put("cabinetNo", session.getCabinet().getCabinetNo());
            snap.put("openedAt", fmt(session.getOpenedAt()));
            snap.put("closedAt", fmt(session.getClosedAt()));
            snap.put("chargedAt", order != null ? fmt(order.getChargedAt()) : null);
            snap.put("weightDeltaGram", session.getWeightDeltaGram());
            snap.put("weightRestored", a.weightRestored());
            snap.put("focusChannel", channelCode);
            snap.put("initialVerdict", a.verdict().name());
            snap.put("reasons", a.reasons());
            List<Map<String, Object>> ev = new ArrayList<>();
            for (RecognitionEvent e : events) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("detectedAt", fmt(e.getDetectedAt()));
                m.put("source", e.getSource().name());
                m.put("action", e.getAction().name());
                m.put("channel", e.getChannelCode());
                m.put("sku", e.getProduct() != null ? e.getProduct().getSku() : null);
                m.put("qty", e.getQty());
                m.put("confidence", e.getConfidence());
                m.put("weightGram", e.getWeightGram());
                m.put("video", e.getVideoClipUrl() != null
                        ? e.getVideoClipUrl() + "[" + e.getClipStartSec() + "-"
                          + e.getClipEndSec() + "s]" : null);
                m.put("lateArrival", e.isLateArrival());
                ev.add(m);
            }
            snap.put("events", ev);
            List<Map<String, Object>> oi = new ArrayList<>();
            for (OrderItem i : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sku", i.getProduct() != null ? i.getProduct().getSku() : null);
                m.put("batchNo", i.getBatch() != null ? i.getBatch().getBatchNo() : null);
                m.put("channel", i.getChannelCode());
                m.put("qty", i.getQty());
                m.put("unitPrice", i.getUnitPrice());
                oi.add(m);
            }
            snap.put("orderItems", oi);
            return objectMapper.writeValueAsString(snap);
        } catch (Exception e) {
            log.warn("证据快照序列化失败", e);
            return "{}";
        }
    }

    private Long doorToCharge(DoorSession session, OrderRecord order) {
        if (order == null || order.getChargedAt() == null || session.getOpenedAt() == null) {
            return null;
        }
        return java.time.Duration.between(session.getOpenedAt(), order.getChargedAt()).toMillis();
    }

    private void logAction(ReviewCase rc, String actor, String action, String detail) {
        actionLogRepository.save(ReviewActionLog.builder()
                .reviewCase(rc).action(action)
                .actor(actor == null ? "system" : actor)
                .detail(detail).actionAt(LocalDateTime.now()).build());
    }

    private ReviewCase must(String no) {
        return reviewRepository.findByReviewNo(no)
                .orElseThrow(() -> ApiException.notFound("复核单不存在"));
    }

    private ReviewCase lockCase(String no) {
        ReviewCase rc = must(no);
        return reviewRepository.findById(rc.getId()).orElseThrow();
    }

    private void ensureOpen(ReviewCase rc) {
        if (rc.getStatus() != ReviewStatus.AUTO_PENDING
                && rc.getStatus() != ReviewStatus.MANUAL_REVIEW) {
            throw ApiException.conflict("复核单已结案（" + rc.getStatus() + "），不可重复操作");
        }
    }

    private AppUser mustHandler(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> ApiException.badRequest("操作账号不存在"));
    }

    private String fmt(LocalDateTime t) {
        return t == null ? null : t.format(FMT);
    }

    private String genNo(String prefix) {
        return com.cabinet.util.BizNos.next(prefix);
    }
}
