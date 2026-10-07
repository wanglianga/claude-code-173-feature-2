package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 统一退款执行器：投诉直退与误扣款复核共用。
 * 幂等三道防线：
 * 1) 订单行级悲观锁，串行化同一订单的并发/重复点击；
 * 2) refund_record.idempotency_key 唯一约束（投诉/复核各自稳定幂等键）；
 * 3) 账户流水 account_txn.idempotency_key 唯一约束，退款回补只发生一次。
 * 退款成功后同一事务内同步修正：订单状态、账户余额、货道库存（收回回补/未收回报损）、
 * 批次台账、会话状态；若订单批次已进入供应商已结算周期，记录跨期修正影响。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final OrderRecordRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ChannelStockRepository stockRepository;
    private final ChannelRepository channelRepository;
    private final LossRecordRepository lossRepository;
    private final BatchLedgerService ledgerService;
    private final RefundRecordRepository refundRecordRepository;
    private final AccountService accountService;
    private final SettlementItemRepository settlementItemRepository;
    private final DoorSessionRepository sessionRepository;
    private final BizEventPublisher bizEventPublisher;
    private final ObjectMapper objectMapper;

    public record RefundLine(Long orderItemId, Integer qty) {}

    public record RefundResult(RefundRecord refundRecord, BigDecimal amount,
                               Map<Long, Integer> refundedQtyByItem, boolean goodsReturned,
                               String settlementImpact, boolean deduplicated) {}

    /**
     * 执行退款。
     * @param idempotencyKey 业务稳定键（如 REVIEW:{reviewId} 或 COMPLAINT:{complaintId}）
     * @param lines 指定行退款数量；null/空表示整单退剩余可退数量
     */
    @Transactional
    public RefundResult execute(OrderRecord order, Complaint complaint, ReviewCase reviewCase,
                                String idempotencyKey, List<RefundLine> lines,
                                boolean goodsReturned, String reason) {
        // 幂等快路径：同一业务键已退过，直接回传原结果（重复点击/补传重试安全）
        RefundRecord existing = refundRecordRepository.findByIdempotencyKey(idempotencyKey)
                .orElse(null);
        if (existing != null) {
            Map<Long, Integer> map = new LinkedHashMap<>();
            // 已存在退款视为不再重复退
            return new RefundResult(existing, existing.getAmount(), map,
                    existing.isGoodsReturned(), existing.getSettlementImpact(), true);
        }

        OrderRecord locked = orderRepository.lockById(order.getId())
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        // 锁内复查
        existing = refundRecordRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return new RefundResult(existing, existing.getAmount(), new LinkedHashMap<>(),
                    existing.isGoodsReturned(), existing.getSettlementImpact(), true);
        }
        if (locked.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.conflict("订单已取消，无扣款可退");
        }
        if (locked.getStatus() == OrderStatus.PENDING_REVIEW) {
            throw ApiException.conflict("订单尚未扣款（识别存疑挂起），无需退款，应走复核否定");
        }

        List<OrderItem> orderItems = orderItemRepository.findByOrderId(locked.getId());
        BigDecimal refundTotal = BigDecimal.ZERO;
        Map<Long, Integer> refundedByItem = new LinkedHashMap<>();
        List<Map<String, Object>> detailRows = new ArrayList<>();
        boolean settledImpact = false;

        for (OrderItem oi : orderItems) {
            int refundQty;
            if (lines == null || lines.isEmpty()) {
                refundQty = oi.getQty() - oi.getRefundedQty();
            } else {
                refundQty = lines.stream()
                        .filter(r -> r.orderItemId() != null && r.orderItemId().equals(oi.getId()))
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
            refundedByItem.put(oi.getId(), refundQty);

            if (oi.getBatch() != null
                    && settlementItemRepository.findByBatchId(oi.getBatch().getId()).stream()
                            .anyMatch(si -> si.getSettlement().getStatus() == SettlementStatus.CONFIRMED
                                    || si.getSettlement().getStatus() == SettlementStatus.PAID)) {
                settledImpact = true;
            }

            if (goodsReturned) {
                Channel channel = channelRepository.findByCabinetIdAndCode(
                        locked.getCabinet().getId(), oi.getChannelCode()).orElse(null);
                ChannelStock stock = channel == null ? null : stockRepository
                        .findByChannelIdAndBatchId(channel.getId(), oi.getBatch().getId())
                        .orElse(null);
                if (stock == null && channel != null) {
                    stock = stockRepository.save(ChannelStock.builder()
                            .channel(channel).batch(oi.getBatch()).product(oi.getProduct())
                            .qtyOnHand(0).qtyQuarantined(0).build());
                }
                if (stock != null) {
                    stock.setQtyOnHand(stock.getQtyOnHand() + refundQty);
                    stockRepository.save(stock);
                }
                ledgerService.record(oi.getBatch(), locked.getCabinet(),
                        LedgerType.REFUND_RETURN, refundQty, lineRefund,
                        reviewCase != null ? "REVIEW" : "COMPLAINT",
                        reviewCase != null ? reviewCase.getId() : complaint.getId(),
                        "误扣款复核退款-商品收回 " + reason);
            } else {
                BigDecimal cost = oi.getBatch() != null && oi.getBatch().getCostPrice() != null
                        ? oi.getBatch().getCostPrice().multiply(BigDecimal.valueOf(refundQty))
                        : BigDecimal.ZERO;
                lossRepository.save(LossRecord.builder()
                        .lossNo(genNo("LS"))
                        .cabinet(locked.getCabinet())
                        .product(oi.getProduct()).batch(oi.getBatch())
                        .reason(LossReason.REFUND_NO_RETURN)
                        .qty(refundQty).costAmount(cost)
                        .liability("OPERATOR")
                        .bizType(reviewCase != null ? "REVIEW" : "COMPLAINT")
                        .bizId(reviewCase != null ? reviewCase.getId() : complaint.getId())
                        .remark("误判退款，商品未收回").build());
                if (oi.getBatch() != null) {
                    ledgerService.record(oi.getBatch(), locked.getCabinet(),
                            LedgerType.LOSS_OUT, -refundQty, cost,
                            reviewCase != null ? "REVIEW" : "COMPLAINT",
                            reviewCase != null ? reviewCase.getId() : complaint.getId(),
                            "误扣款复核退款-商品未收回 " + reason);
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderItemId", oi.getId());
            row.put("sku", oi.getProduct() != null ? oi.getProduct().getSku() : null);
            row.put("batchNo", oi.getBatch() != null ? oi.getBatch().getBatchNo() : null);
            row.put("channelCode", oi.getChannelCode());
            row.put("qty", refundQty);
            row.put("amount", lineRefund);
            row.put("goodsReturned", goodsReturned);
            detailRows.add(row);
        }

        if (refundTotal.signum() <= 0) {
            throw ApiException.conflict("没有可退款的商品行（可能已全额退款）");
        }

        locked.setRefundedAmount(locked.getRefundedAmount().add(refundTotal));
        boolean allRefunded = orderItems.stream()
                .allMatch(oi -> oi.getRefundedQty() >= oi.getQty());
        locked.setStatus(allRefunded ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
        orderRepository.save(locked);

        DoorSession session = locked.getSession();
        if (session != null) {
            session.setStatus(SessionStatus.SETTLED_REFUNDED);
            sessionRepository.save(session);
        }

        String impact = settledImpact ? "订单批次已进入供应商已确认/已付结算周期，"
                + "本次退款作为跨期修正（台账 REFUND_RETURN/LOSS_OUT 留痕，下期结算冲减）" : null;

        String detailJson;
        try {
            detailJson = objectMapper.writeValueAsString(detailRows);
        } catch (Exception e) {
            detailJson = detailRows.toString();
        }

        RefundRecord refund;
        try {
            refund = refundRecordRepository.save(RefundRecord.builder()
                    .refundNo(genNo("RF"))
                    .order(locked).reviewCase(reviewCase).complaint(complaint)
                    .amount(refundTotal).idempotencyKey(idempotencyKey)
                    .refundTxnNo("RFD" + System.nanoTime())
                    .goodsReturned(goodsReturned)
                    .detailJson(detailJson)
                    .settlementImpact(impact)
                    .refundedAt(LocalDateTime.now())
                    .build());
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 唯一约束兜底：并发下已退，不允许二次退款
            throw ApiException.conflict("退款已处理，请勿重复提交");
        }

        // 账户余额回补（幂等键与退款单一致）
        if (locked.getUserCode() != null) {
            accountService.refund(locked.getUserCode(), refundTotal,
                    "ACCOUNT:" + idempotencyKey, refund.getRefundNo(),
                    "误扣款退款回补 " + (reason == null ? "" : reason));
        }

        bizEventPublisher.publish("ORDER_REFUNDED", locked.getOrderNo(),
                locked.getCabinet() != null ? locked.getCabinet().getCabinetNo() : null, null,
                (reviewCase != null ? "复核 " : "投诉 ") + idempotencyKey + " 退款 " + refundTotal,
                refundTotal);

        return new RefundResult(refund, refundTotal, refundedByItem, goodsReturned, impact, false);
    }

    private String genNo(String prefix) {
        return com.cabinet.util.BizNos.next(prefix);
    }
}
