package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.dto.ChargeBasisView;
import com.cabinet.dto.EvidenceView;
import com.cabinet.dto.ReviewEvidenceView;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 误扣款复核与客服售后：
 * - 提交即自动研判（系统建议快照，不替代人工）；
 * - 证据回放：开门、拿取、重量变化、摄像头识别、货道盘点、扣款时间一屏聚合；
 * - 判责退款：订单状态、账户余额、货道库存、识别样本四处同步修正；
 *   重复点击/重复申诉/设备补传均不会二次退款（状态机 + 钱包幂等键双保险）；
 * - 退款归责货道，触发货道健康监控（区分单次误识别与设备故障）；
 * - 已结算订单的退款修正、重复申诉全程审计留痕。
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
    private final ChannelStockRepository stockRepository;
    private final ChannelRepository channelRepository;
    private final AppUserRepository userRepository;
    private final LossRecordRepository lossRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final BatchLedgerService ledgerService;
    private final BizEventPublisher bizEventPublisher;
    private final WalletService walletService;
    private final ReviewAnalysisService analysisService;
    private final ReviewAuditService auditService;
    private final ChannelHealthService channelHealthService;

    @Transactional
    public Complaint submit(String sessionNo, String category, String description) {
        DoorSession session = sessionRepository.findBySessionNo(sessionNo)
                .orElseThrow(() -> ApiException.notFound("开门会话不存在"));
        OrderRecord order = orderRepository.findBySessionId(session.getId()).orElse(null);

        // 重复申诉：保留每次申诉记录并关联首单，后续判责不会二次退款
        List<Complaint> existing = complaintRepository.findBySessionIdOrderByIdAsc(session.getId());
        Long duplicateOf = existing.isEmpty() ? null : existing.get(0).getId();

        Complaint complaint = Complaint.builder()
                .complaintNo(genNo("CP"))
                .session(session).order(order)
                .userCode(session.getUserCode()).userName(session.getUserName())
                .category(category).description(description)
                .status(ComplaintStatus.SUBMITTED)
                .duplicateOfId(duplicateOf)
                .build();
        // 提交即系统研判，快照留存（人工判责时可对照原始判断）
        try {
            var verdict = analysisService.analyze(session, order);
            complaint.setSystemVerdict(verdict.suggestion().name());
            complaint = complaintRepository.save(complaint);
            auditService.log("COMPLAINT", complaint.getComplaintNo(), "AUTO_ANALYZE", "system",
                    "系统研判 " + verdict.suggestion() + "；" + String.join("；", verdict.reasons()));
        } catch (Exception e) {
            complaint = complaintRepository.save(complaint);
        }
        final Complaint saved = complaint;
        auditService.log("COMPLAINT", saved.getComplaintNo(), "SUBMIT",
                saved.getUserCode(), category + ": " + description);
        if (duplicateOf != null) {
            auditService.log("COMPLAINT", saved.getComplaintNo(), "DUPLICATE_APPEAL",
                    saved.getUserCode(), "重复申诉，首单投诉 ID=" + duplicateOf
                            + "；历史处理结果保留，本单判责不会二次退款");
        }
        return saved;
    }

    /** 组装售后证据回放视图（基础版，保持兼容） */
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
            ledgers.addAll(ledgerRepository.findByBatchIdOrderByIdAsc(oi.getBatch().getId()));
        }
        ledgers.sort(java.util.Comparator.comparing(BatchLedger::getId));

        List<TempLog> temps = tempLogRepository
                .findTop200ByCabinetIdOrderByRecordedAtDesc(session.getCabinet().getId());

        return new EvidenceView(c, session, order, items, events, ledgers, temps);
    }

    /**
     * 复核证据工作台：开门、拿取、重量变化、摄像头识别、货道盘点、扣款时间
     * 与系统研判建议一次聚合返回。
     */
    @Transactional(readOnly = true)
    public ReviewEvidenceView reviewEvidence(String complaintNo) {
        Complaint c = complaintRepository.findByComplaintNo(complaintNo)
                .orElseThrow(() -> ApiException.notFound("投诉单不存在"));
        DoorSession session = c.getSession();
        OrderRecord order = c.getOrder();
        List<OrderItem> items = order != null
                ? orderItemRepository.findByOrderId(order.getId()) : List.of();
        List<RecognitionEvent> events =
                eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId());

        var verdict = analysisService.analyze(session, order);
        var channelStocks = analysisService.channelStockSnapshot(session, items);
        var stocktakeLines = analysisService.recentStocktakeLines(session, items);

        // 扣款时间线：扣款流水 + 退款流水
        ReviewEvidenceView.ChargeTiming timing = null;
        if (order != null) {
            WalletTxn chargeTxn = walletService.txnsOf(order.getUserCode()).stream()
                    .filter(t -> "ORDER".equals(t.getBizType()) && t.getBizId().equals(order.getId()))
                    .findFirst().orElse(null);
            timing = new ReviewEvidenceView.ChargeTiming(
                    order.getChargedAt(), order.getPaymentTxnNo(),
                    chargeTxn != null ? chargeTxn.getTxnNo() : null,
                    order.getTotalAmount(), order.getRefundedAmount());
        }
        List<TempLog> temps = tempLogRepository
                .findTop200ByCabinetIdOrderByRecordedAtDesc(session.getCabinet().getId());
        var audits = auditService.trail("COMPLAINT", complaintNo);

        return new ReviewEvidenceView(c, session, order, items, events,
                verdict, channelStocks, stocktakeLines, timing, temps, audits);
    }

    /** 用户侧扣款依据：证据充分时向用户展示为什么被扣款 */
    @Transactional(readOnly = true)
    public ChargeBasisView chargeBasis(String orderNo) {
        OrderRecord order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        DoorSession session = order.getSession();
        List<RecognitionEvent> events =
                eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId());

        List<ChargeBasisView.ChargeBasisItem> basisItems = items.stream()
                .map(i -> new ChargeBasisView.ChargeBasisItem(
                        i.getProduct().getSku(), i.getProduct().getName(),
                        i.getQty(), i.getUnitPrice(), i.getSubtotal(), i.getChannelCode()))
                .toList();

        List<String> summary = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        events.stream().filter(e -> e.getAction() == DetectAction.PICK)
                .forEach(e -> sources.add(e.getSource().name()));
        if (!sources.isEmpty()) {
            summary.add("识别源 " + String.join("/", sources) + " 均记录到拿取动作");
        }
        int delta = session.getWeightDeltaGram() != null ? session.getWeightDeltaGram() : 0;
        if (delta != 0) {
            summary.add("关门时柜内净重减少 " + Math.abs(delta) + "g，与计费商品重量相符");
        }
        summary.add("扣款时间 " + order.getChargedAt() + "，支付流水 " + order.getPaymentTxnNo());

        List<ChargeBasisView.VideoClip> clips = events.stream()
                .filter(e -> e.getVideoClipUrl() != null)
                .map(e -> new ChargeBasisView.VideoClip(e.getVideoClipUrl(),
                        e.getClipStartSec(), e.getClipEndSec(),
                        e.getSource().name(), e.getChannelCode()))
                .toList();

        return new ChargeBasisView(order.getOrderNo(), order.getTotalAmount(),
                order.getChargedAt(), order.getPaymentTxnNo(),
                session.getOpenedAt(), session.getClosedAt(),
                basisItems, summary, clips);
    }

    /**
     * 判责处理：退款或驳回。幂等 —— 已结单重复提交直接返回原结果，不二次退款。
     * refundItems: orderItemId -> 退款数量（为空且 grantRefund=true 则整单退）
     * goodsReturned: 商品是否已收回；未收回 -> 报损（退款未收回）
     */
    @Transactional
    public Complaint resolve(String complaintNo, String handlerUsername, boolean grantRefund,
                             List<RefundLine> refundItems, boolean goodsReturned,
                             String resolution) {
        // 悲观锁 + 状态机：重复点击/并发提交只生效一次
        Complaint complaint = complaintRepository.lockByComplaintNo(complaintNo)
                .orElseThrow(() -> ApiException.notFound("投诉单不存在"));
        if (complaint.getStatus() == ComplaintStatus.RESOLVED_REFUNDED
                || complaint.getStatus() == ComplaintStatus.RESOLVED_REJECTED) {
            auditService.log("COMPLAINT", complaintNo, "RESOLVE_DUPLICATE_IGNORED",
                    handlerUsername, "重复提交判责，原处理结果保持不变（退款 "
                    + complaint.getRefundAmount() + "），未二次退款");
            return complaint;
        }
        AppUser handler = userRepository.findByUsername(handlerUsername)
                .orElseThrow(() -> ApiException.badRequest("客服账号不存在"));
        complaint.setHandler(handler);
        complaint.setStatus(grantRefund
                ? ComplaintStatus.RESOLVED_REFUNDED : ComplaintStatus.RESOLVED_REJECTED);
        complaint.setResolution(resolution);
        complaint.setResolvedAt(LocalDateTime.now());

        OrderRecord order = complaint.getOrder();
        BigDecimal refundTotal = BigDecimal.ZERO;
        List<String> refundDetail = new ArrayList<>();
        Set<String> refundedChannels = new LinkedHashSet<>();

        if (grantRefund && order != null
                && order.getStatus() != OrderStatus.REFUNDED
                && order.getStatus() != OrderStatus.CANCELLED) {
            List<OrderItem> orderItems = orderItemRepository.findByOrderId(order.getId());
            for (OrderItem oi : orderItems) {
                int refundQty;
                if (refundItems == null || refundItems.isEmpty()) {
                    refundQty = oi.getQty() - oi.getRefundedQty();
                } else {
                    refundQty = refundItems.stream()
                            .filter(r -> r.orderItemId() == oi.getId())
                            .findFirst().map(RefundLine::qty).orElse(0);
                }
                if (refundQty <= 0) continue;
                if (refundQty > oi.getQty() - oi.getRefundedQty()) {
                    throw ApiException.badRequest("退款数量超过可退数量");
                }
                oi.setRefundedQty(oi.getRefundedQty() + refundQty);
                orderItemRepository.save(oi);
                BigDecimal lineRefund = oi.getUnitPrice().multiply(BigDecimal.valueOf(refundQty));
                refundTotal = refundTotal.add(lineRefund);
                refundDetail.add(oi.getProduct().getSku() + " x" + refundQty + "=" + lineRefund);
                refundedChannels.add(oi.getChannelCode());

                // 库存修正：收回则回补货道库存，未收回则登记报损
                if (goodsReturned) {
                    ChannelStock stock = stockRepository
                            .findByChannelIdAndBatchId(
                                    channelId(oi), oi.getBatch().getId())
                            .orElse(null);
                    if (stock != null) {
                        stock.setQtyOnHand(stock.getQtyOnHand() + refundQty);
                        stockRepository.save(stock);
                    }
                    ledgerService.record(oi.getBatch(), order.getCabinet(),
                            LedgerType.REFUND_RETURN, refundQty, lineRefund,
                            "COMPLAINT", complaint.getId(),
                            "投诉退款商品收回 " + complaintNo);
                } else {
                    BigDecimal cost = oi.getBatch().getCostPrice() != null
                            ? oi.getBatch().getCostPrice().multiply(BigDecimal.valueOf(refundQty))
                            : BigDecimal.ZERO;
                    lossRepository.save(LossRecord.builder()
                            .lossNo(genNo("LS"))
                            .cabinet(order.getCabinet())
                            .product(oi.getProduct()).batch(oi.getBatch())
                            .reason(LossReason.REFUND_NO_RETURN)
                            .qty(refundQty).costAmount(cost)
                            .liability("OPERATOR")
                            .bizType("COMPLAINT").bizId(complaint.getId())
                            .remark("误判退款，商品未收回").build());
                    ledgerService.record(oi.getBatch(), order.getCabinet(),
                            LedgerType.LOSS_OUT, -refundQty, cost,
                            "COMPLAINT", complaint.getId(),
                            "投诉退款商品未收回 " + complaintNo);
                }
                // 识别样本同步修正：该货道该商品的「拿起」识别标记为误识别，供模型纠偏
                markSamples(complaint.getSession(), oi, SampleVerdict.MISJUDGED,
                        "投诉 " + complaintNo + " 退款成立，判定误识别");
            }

            // 账户余额修正：退款贷记（幂等键 COMPLAINT+id+REFUND，重复提交不二次退款）
            if (refundTotal.signum() > 0) {
                walletService.refund(complaint.getUserCode(), refundTotal,
                        "COMPLAINT", complaint.getId(),
                        "投诉 " + complaintNo + " 复核退款");
            }

            order.setRefundedAmount(order.getRefundedAmount().add(refundTotal));
            boolean allRefunded = orderItems.stream()
                    .allMatch(oi -> oi.getRefundedQty() >= oi.getQty());
            order.setStatus(allRefunded ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
            orderRepository.save(order);

            DoorSession session = complaint.getSession();
            session.setStatus(SessionStatus.SETTLED_REFUNDED);
            sessionRepository.save(session);

            // 已结算订单修正留痕：若涉及批次已进入确认/付款的结算单，记录修正口径
            noteSettledOrderCorrection(complaint, orderItems);

            // 货道健康：退款归责货道，区分单次误识别与设备故障
            for (String channelCode : refundedChannels) {
                channelHealthService.onRefundAttributed(
                        order.getCabinet(), channelCode, complaint);
            }

            bizEventPublisher.publish("ORDER_REFUNDED", order.getOrderNo(),
                    order.getCabinet().getCabinetNo(), null,
                    "投诉 " + complaintNo + " 退款 " + refundTotal, refundTotal);
        } else if (grantRefund) {
            // 订单已退过（如重复申诉时首单已退款）：本单不再退款，仅留痕
            auditService.log("COMPLAINT", complaintNo, "WALLET_TXN_SKIPPED",
                    handlerUsername, "订单当前状态 " + (order == null ? "无订单" : order.getStatus())
                            + "，本次判责退款金额 0，未重复退款");
        }

        if (!grantRefund) {
            // 证据充分驳回：识别样本标记为确认正确
            markAllSamples(complaint.getSession(), SampleVerdict.CONFIRMED,
                    "投诉 " + complaintNo + " 驳回，人工确认识别正确");
        }

        complaint.setRefundAmount(refundTotal);
        complaint.setRefundDetailJson(String.join("; ", refundDetail));
        Complaint saved = complaintRepository.save(complaint);
        auditService.log("COMPLAINT", complaintNo,
                grantRefund ? "RESOLVE_REFUND" : "RESOLVE_REJECT", handlerUsername,
                String.format("人工判责%s，退款 %s，商品%s收回；系统原始研判=%s；处理意见: %s",
                        grantRefund ? "退款" : "驳回", refundTotal,
                        goodsReturned ? "已" : "未",
                        complaint.getSystemVerdict(), resolution));
        return saved;
    }

    /** 已结算订单：涉及批次若已进入确认/付款结算单，审计注明修正计入后续周期 */
    private void noteSettledOrderCorrection(Complaint complaint, List<OrderItem> items) {
        Set<String> settledRefs = new LinkedHashSet<>();
        for (OrderItem oi : items) {
            for (SettlementItem si : settlementItemRepository.findByBatchId(oi.getBatch().getId())) {
                SettlementStatus st = si.getSettlement().getStatus();
                if (st == SettlementStatus.CONFIRMED || st == SettlementStatus.PAID) {
                    settledRefs.add(si.getSettlement().getSettlementNo() + "(" + st + ")");
                }
            }
        }
        if (!settledRefs.isEmpty()) {
            auditService.log("COMPLAINT", complaint.getComplaintNo(),
                    "SETTLED_ORDER_CORRECTION", "system",
                    "订单涉及批次已进入结算单 " + String.join(",", settledRefs)
                            + "；本次退款修正不改动原结算单，台账 REFUND_RETURN/LOSS_OUT 计入后续结算周期");
        }
    }

    private void markSamples(DoorSession session, OrderItem oi,
                             SampleVerdict verdict, String note) {
        eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId()).stream()
                .filter(e -> e.getAction() == DetectAction.PICK
                        && e.getChannelCode() != null
                        && e.getChannelCode().equals(oi.getChannelCode())
                        && e.getProduct() != null
                        && e.getProduct().getId().equals(oi.getProduct().getId()))
                .forEach(e -> {
                    e.setVerdict(verdict);
                    e.setVerdictNote(note);
                    eventRepository.save(e);
                });
    }

    private void markAllSamples(DoorSession session, SampleVerdict verdict, String note) {
        eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId()).stream()
                .filter(e -> e.getVerdict() == SampleVerdict.NONE)
                .forEach(e -> {
                    e.setVerdict(verdict);
                    e.setVerdictNote(note);
                    eventRepository.save(e);
                });
    }

    private Long channelId(OrderItem oi) {
        return channelRepository
                .findByCabinetIdAndCode(oi.getOrder().getCabinet().getId(), oi.getChannelCode())
                .map(Channel::getId).orElse(null);
    }

    private String genNo(String prefix) {
        return prefix + LocalDate.now().toString().replace("-", "")
                + String.format("%04d", System.nanoTime() % 10000);
    }

    public record RefundLine(Long orderItemId, Integer qty) {}
}
