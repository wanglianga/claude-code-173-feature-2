package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 下架/报损服务：
 * - 临期定时扫描生成临期下架单；
 * - 温控隔离、过期、卡货损坏、放错货道纠正可创建下架单；
 * - 执行下架扣减货道批次库存与批次台账；
 * - 去向为报废的生成报损记录（含损耗原因与责任归属，供供应商/运营按批次查看）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemovalService {

    private final RemovalOrderRepository removalRepository;
    private final RemovalItemRepository removalItemRepository;
    private final ChannelStockRepository stockRepository;
    private final ProductBatchRepository batchRepository;
    private final LossRecordRepository lossRepository;
    private final AppUserRepository userRepository;
    private final ChannelRepository channelRepository;
    private final BatchLedgerService ledgerService;
    private final AlertService alertService;
    private final BizEventPublisher bizEventPublisher;

    /** 扫描临期/过期在库批次，为每台有货柜机生成下架单 */
    @Transactional
    public int scanExpiry(int nearExpiryDays) {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(nearExpiryDays);
        List<ProductBatch> batches = batchRepository
                .findByStatusAndExpiryDateLessThanEqual(BatchStatus.IN_STOCK, threshold);
        int created = 0;
        for (ProductBatch batch : batches) {
            List<ChannelStock> stocks = stockRepository.findByBatchId(batch.getId());
            // 按柜机聚合成下架单
            java.util.Map<Long, List<ChannelStock>> byCabinet = new java.util.LinkedHashMap<>();
            for (ChannelStock s : stocks) {
                int avail = s.getQtyOnHand();
                if (avail <= 0) continue;
                byCabinet.computeIfAbsent(s.getChannel().getCabinet().getId(), k -> new ArrayList<>()).add(s);
            }
            for (var entry : byCabinet.entrySet()) {
                Cabinet cabinet = entry.getValue().get(0).getChannel().getCabinet();
                boolean expired = !batch.getExpiryDate().isAfter(today);
                RemovalReason reason = expired ? RemovalReason.EXPIRED : RemovalReason.NEAR_EXPIRY;
                if (expired) {
                    alertService.raise(cabinet, AlertType.EXPIRED, "CRITICAL",
                            "批次 " + batch.getBatchNo() + " 已过期仍在柜 " + cabinet.getCabinetNo(),
                            "BATCH", batch.getId());
                } else {
                    alertService.raise(cabinet, AlertType.NEAR_EXPIRY, "WARN",
                            "批次 " + batch.getBatchNo() + " 将于 " + batch.getExpiryDate() + " 临期",
                            "BATCH", batch.getId());
                }
                RemovalOrder order = RemovalOrder.builder()
                        .orderNo(genNo("RM"))
                        .cabinet(cabinet)
                        .triggeredBy("SYSTEM")
                        .reason(reason)
                        .status(RemovalStatus.PENDING)
                        .scheduledAt(LocalDateTime.now())
                        .remark(reason == RemovalReason.EXPIRED ? "过期紧急下架" : "临期预防性下架")
                        .build();
                removalRepository.save(order);
                int total = 0;
                for (ChannelStock s : entry.getValue()) {
                    RemovalItem ri = RemovalItem.builder()
                            .removalOrder(order)
                            .channel(s.getChannel())
                            .product(s.getProduct())
                            .batch(batch)
                            .plannedQty(s.getQtyOnHand())
                            .build();
                    removalItemRepository.save(ri);
                    total += s.getQtyOnHand();
                }
                bizEventPublisher.publish("REMOVAL_CREATED", order.getOrderNo(),
                        cabinet.getCabinetNo(), batch.getBatchNo(),
                        reason + " 下架单生成，数量 " + total, null);
                created++;
            }
        }
        return created;
    }

    /**
     * 手工创建下架单（温控异常 / 卡货损坏 / 放错货道纠正 / 召回）。
     * items: channelCode + qty
     */
    @Transactional
    public RemovalOrder createManualOrder(Cabinet cabinet, RemovalReason reason,
                                          List<ManualRemovalLine> lines) {
        RemovalOrder order = RemovalOrder.builder()
                .orderNo(genNo("RM"))
                .cabinet(cabinet)
                .triggeredBy("OPERATOR")
                .reason(reason)
                .status(RemovalStatus.PENDING)
                .scheduledAt(LocalDateTime.now())
                .build();
        removalRepository.save(order);
        for (ManualRemovalLine line : lines) {
            Channel channel = channelRepository
                    .findByCabinetIdAndCode(cabinet.getId(), line.channelCode())
                    .orElseThrow(() -> ApiException.badRequest("货道不存在: " + line.channelCode()));
            List<ChannelStock> stocks = stockRepository.findByChannelId(channel.getId());
            ChannelStock target;
            if (line.batchNo() != null) {
                ProductBatch b = batchRepository.findByBatchNo(line.batchNo())
                        .orElseThrow(() -> ApiException.badRequest("批次不存在"));
                target = stocks.stream().filter(s -> s.getBatch().getId().equals(b.getId()))
                        .findFirst().orElseThrow(() -> ApiException.badRequest("货道无该批次"));
            } else {
                // 卡货/温控等按 FEFO 取最早批次
                target = stocks.stream()
                        .filter(s -> s.getQtyOnHand() > 0)
                        .min(java.util.Comparator.comparing(s -> s.getBatch().getExpiryDate()))
                        .orElseThrow(() -> ApiException.badRequest("货道无在库"));
            }
            removalItemRepository.save(RemovalItem.builder()
                    .removalOrder(order)
                    .channel(channel)
                    .product(target.getProduct())
                    .batch(target.getBatch())
                    .plannedQty(line.qty())
                    .build());
        }
        return order;
    }

    /** 执行下架：扣库存、台账、报废登记报损；退货去向不产生报损 */
    @Transactional
    public RemovalOrder execute(String removalNo, String executorUsername,
                                Disposition defaultDisposition, String remark) {
        RemovalOrder order = removalRepository.findByOrderNo(removalNo)
                .orElseThrow(() -> ApiException.notFound("下架单不存在"));
        if (order.getStatus() == RemovalStatus.EXECUTED) {
            throw ApiException.conflict("下架单已执行");
        }
        AppUser executor = executorUsername != null
                ? userRepository.findByUsername(executorUsername).orElse(null) : null;
        List<RemovalItem> items = removalItemRepository.findByRemovalOrderId(order.getId());

        for (RemovalItem item : items) {
            int qty = item.getPlannedQty();
            ChannelStock stock = stockRepository
                    .findByChannelIdAndBatchId(item.getChannel().getId(), item.getBatch().getId())
                    .orElseThrow(() -> ApiException.conflict("库存记录缺失"));
            if (stock.getQtyOnHand() < qty) {
                throw ApiException.conflict(String.format("货道 %s 批次 %s 库存不足(%d<%d)",
                        item.getChannel().getCode(), item.getBatch().getBatchNo(),
                        stock.getQtyOnHand(), qty));
            }
            stock.setQtyOnHand(stock.getQtyOnHand() - qty);
            stock.setQtyQuarantined(Math.max(0, stock.getQtyQuarantined() - qty));
            stockRepository.save(stock);

            Disposition disp = item.getDisposition() != null ? item.getDisposition() : defaultDisposition;
            item.setExecutedQty(qty);
            item.setDisposition(disp);

            ProductBatch batch = item.getBatch();
            BigDecimal cost = batch.getCostPrice() != null
                    ? batch.getCostPrice().multiply(BigDecimal.valueOf(qty)) : BigDecimal.ZERO;
            ledgerService.record(batch, order.getCabinet(), LedgerType.REMOVAL_OUT, -qty,
                    cost, "REMOVAL_ORDER", order.getId(),
                    order.getReason() + " 下架 " + item.getChannel().getCode());

            if (disp == Disposition.SCRAP) {
                LossReason lossReason = mapLossReason(order.getReason());
                String liability = order.getReason() == RemovalReason.TEMP_ABUSE
                        || order.getReason() == RemovalReason.DAMAGED
                        || order.getReason() == RemovalReason.WRONG_CHANNEL
                        ? "OPERATOR" : "SUPPLIER";
                LossRecord loss = LossRecord.builder()
                        .lossNo(genNo("LS"))
                        .cabinet(order.getCabinet())
                        .product(item.getProduct())
                        .batch(batch)
                        .reason(lossReason)
                        .qty(qty)
                        .costAmount(cost)
                        .liability(liability)
                        .bizType("REMOVAL_ORDER")
                        .bizId(order.getId())
                        .remark(order.getReason().name())
                        .build();
                lossRepository.save(loss);
                bizEventPublisher.publish("LOSS_RECORDED", loss.getLossNo(),
                        order.getCabinet().getCabinetNo(), batch.getBatchNo(),
                        lossReason + " x" + qty, cost);
            }
        }

        order.setStatus(RemovalStatus.EXECUTED);
        order.setExecutor(executor);
        order.setExecutedAt(LocalDateTime.now());
        if (remark != null) order.setRemark(remark);
        removalRepository.save(order);

        // 批次若已无在库，状态随下架关闭
        for (ProductBatch b : items.stream().map(RemovalItem::getBatch).distinct().toList()) {
            int remain = stockRepository.sumOnHandByBatch(b.getId());
            if (remain <= 0 && b.getStatus() == BatchStatus.IN_STOCK) {
                b.setStatus(BatchStatus.REMOVED);
                batchRepository.save(b);
            }
        }
        return order;
    }

    private LossReason mapLossReason(RemovalReason reason) {
        return switch (reason) {
            case EXPIRED -> LossReason.SCRAP_EXPIRED;
            case NEAR_EXPIRY -> LossReason.SCRAP_NEAR_EXPIRY;
            case TEMP_ABUSE -> LossReason.TEMP_ABUSE;
            case DAMAGED -> LossReason.SCRAP_DAMAGED;
            case WRONG_CHANNEL -> LossReason.SHRINKAGE;
            case RECALLED -> LossReason.SCRAP_DAMAGED;
        };
    }

    private String genNo(String prefix) {
        return com.cabinet.util.BizNos.next(prefix);
    }

    /** 手工下架行 */
    public record ManualRemovalLine(String channelCode, String batchNo, Integer qty) {}
}
