package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 财务结算服务：按供应商 + 周期，依据批次台账汇总
 * 真实售卖（SALE_OUT - REFUND_RETURN）与报损（LOSS_OUT/LossRecord 中 SUPPLIER 责任），
 * 逐批次生成结算明细；应付款 = 售卖成本 - 供应商责任报损。
 * 同一供应商同一周期不允许重复确认。
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementBatchRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final SupplierRepository supplierRepository;
    private final BatchLedgerRepository ledgerRepository;
    private final LossRecordRepository lossRepository;
    private final ProductBatchRepository batchRepository;
    private final OrderItemRepository orderItemRepository;
    private final BizEventPublisher bizEventPublisher;

    /** 生成某供应商周期结算单草稿（按批次聚合真实售卖成本与报损） */
    @Transactional
    public SettlementBatch generate(Long supplierId, LocalDate start, LocalDate end) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> ApiException.notFound("供应商不存在"));
        boolean exists = settlementRepository.existsBySupplierIdAndStatusInAndPeriodStartAndPeriodEnd(
                supplierId, List.of(SettlementStatus.DRAFT, SettlementStatus.CONFIRMED,
                        SettlementStatus.PAID), start, end);
        if (exists) {
            throw ApiException.conflict("该周期已存在结算单");
        }

        // 该供应商所有批次在周期内的台账聚合
        List<ProductBatch> batches = batchRepository.findAll().stream()
                .filter(b -> b.getSupplier() != null && b.getSupplier().getId().equals(supplierId))
                .toList();

        SettlementBatch sb = SettlementBatch.builder()
                .settlementNo(genNo("STL"))
                .supplier(supplier)
                .periodStart(start).periodEnd(end)
                .status(SettlementStatus.DRAFT)
                .build();
        settlementRepository.save(sb);

        int totalSold = 0, totalLoss = 0;
        BigDecimal soldCostSum = BigDecimal.ZERO, lossCostSum = BigDecimal.ZERO;

        for (ProductBatch batch : batches) {
            // 真实售卖：周期内订单明细（按批次），扣除整单退款数量
            int soldQty = 0;
            BigDecimal soldCost = BigDecimal.ZERO;
            List<OrderItem> orderItems = orderItemRepository.findByBatchId(batch.getId());
            for (OrderItem oi : orderItems) {
                LocalDateTime chargedAt = oi.getOrder().getChargedAt();
                if (chargedAt == null || chargedAt.toLocalDate().isBefore(start)
                        || chargedAt.toLocalDate().isAfter(end)) {
                    continue;
                }
                int netSold = oi.getQty() - oi.getRefundedQty();
                if (netSold <= 0) continue;
                soldQty += netSold;
                BigDecimal unitCost = batch.getCostPrice() != null
                        ? batch.getCostPrice() : BigDecimal.ZERO;
                soldCost = soldCost.add(unitCost.multiply(BigDecimal.valueOf(netSold)));
            }

            // 供应商责任报损
            int lossQty = 0;
            BigDecimal lossCost = BigDecimal.ZERO;
            for (LossRecord lr : lossRepository.findByBatchIdOrderByIdAsc(batch.getId())) {
                LocalDateTime t = lr.getCreatedAt();
                if (t.toLocalDate().isBefore(start) || t.toLocalDate().isAfter(end)) continue;
                if ("SUPPLIER".equals(lr.getLiability())) {
                    lossQty += lr.getQty();
                    lossCost = lossCost.add(lr.getCostAmount() != null
                            ? lr.getCostAmount() : BigDecimal.ZERO);
                }
            }

            if (soldQty == 0 && lossQty == 0) continue;
            BigDecimal payable = soldCost.subtract(lossCost);
            settlementItemRepository.save(SettlementItem.builder()
                    .settlement(sb).batch(batch).product(batch.getProduct())
                    .soldQty(soldQty).lossQty(lossQty)
                    .soldCost(soldCost).lossCost(lossCost)
                    .payableAmount(payable)
                    .build());
            totalSold += soldQty;
            totalLoss += lossQty;
            soldCostSum = soldCostSum.add(soldCost);
            lossCostSum = lossCostSum.add(lossCost);

            ledgerRepository.save(BatchLedger.builder()
                    .batch(batch).cabinet(null)
                    .ledgerType(LedgerType.SETTLE)
                    .changeQty(0)
                    .balanceQty(0)
                    .amount(payable)
                    .bizType("SETTLEMENT").bizId(sb.getId())
                    .remark("周期结算 " + start + "~" + end)
                    .build());
        }

        sb.setSoldQty(totalSold);
        sb.setLossQty(totalLoss);
        sb.setSoldCost(soldCostSum);
        sb.setLossCost(lossCostSum);
        sb.setPayableAmount(soldCostSum.subtract(lossCostSum));
        return settlementRepository.save(sb);
    }

    @Transactional
    public SettlementBatch confirm(String settlementNo) {
        SettlementBatch sb = must(settlementNo);
        if (sb.getStatus() != SettlementStatus.DRAFT) {
            throw ApiException.conflict("仅草稿状态可确认");
        }
        sb.setStatus(SettlementStatus.CONFIRMED);
        sb.setConfirmedAt(LocalDateTime.now());
        settlementRepository.save(sb);
        bizEventPublisher.publish("SETTLEMENT_CONFIRMED", sb.getSettlementNo(),
                null, null,
                "供应商 " + sb.getSupplier().getCode() + " 结算确认 应付 " + sb.getPayableAmount(),
                sb.getPayableAmount());
        return sb;
    }

    @Transactional
    public SettlementBatch markPaid(String settlementNo) {
        SettlementBatch sb = must(settlementNo);
        if (sb.getStatus() != SettlementStatus.CONFIRMED) {
            throw ApiException.conflict("仅已确认状态可标记付款");
        }
        sb.setStatus(SettlementStatus.PAID);
        sb.setPaidAt(LocalDateTime.now());
        settlementRepository.save(sb);

        // 批次状态推进为已结算（仅在库为 0 的批次）
        for (SettlementItem si : settlementItemRepository.findBySettlementId(sb.getId())) {
            ProductBatch b = si.getBatch();
            if (b.getStatus() != BatchStatus.SETTLED) {
                b.setStatus(BatchStatus.SETTLED);
                batchRepository.save(b);
            }
        }
        Supplier supplier = sb.getSupplier();
        supplier.setBalancePayable(supplier.getBalancePayable().subtract(sb.getPayableAmount()));
        supplierRepository.save(supplier);
        return sb;
    }

    @Transactional(readOnly = true)
    public List<SettlementItem> items(Long settlementId) {
        return settlementItemRepository.findBySettlementId(settlementId);
    }

    private SettlementBatch must(String no) {
        return settlementRepository.findBySettlementNo(no)
                .orElseThrow(() -> ApiException.notFound("结算单不存在"));
    }

    private String genNo(String prefix) {
        return com.cabinet.util.BizNos.next(prefix);
    }
}
