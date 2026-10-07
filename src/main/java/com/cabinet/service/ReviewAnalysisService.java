package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 误扣款复核研判：把开门、拿取、重量变化、摄像头识别、货道盘点、扣款时间
 * 放在一起交叉验证，给出系统建议（不替代人工判责）：
 * - 重量已恢复但视觉仍认为商品被取走 → 不得直接判定购买，建议转人工（SUSPECT_MISCHARGE）；
 * - 多源一致且重量/库存同步减少 → 证据充分，建议驳回并向用户展示扣款依据（EVIDENCE_SUFFICIENT）；
 * - 其余证据冲突 → 转人工确认（NEED_MANUAL）。
 */
@Service
@RequiredArgsConstructor
public class ReviewAnalysisService {

    private final RecognitionEventRepository eventRepository;
    private final OrderItemRepository orderItemRepository;
    private final BatchLedgerRepository ledgerRepository;
    private final ChannelStockRepository stockRepository;
    private final ChannelRepository channelRepository;
    private final StocktakeOrderRepository stocktakeOrderRepository;
    private final StocktakeItemRepository stocktakeItemRepository;

    @Value("${cabinet.recognition.weight-tolerance-gram:80}")
    private int weightToleranceGram;

    @Transactional(readOnly = true)
    public ReviewVerdict analyze(DoorSession session, OrderRecord order) {
        List<RecognitionEvent> events =
                eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId());
        List<OrderItem> items = order != null
                ? orderItemRepository.findByOrderId(order.getId()) : List.of();

        // 1) 各识别源独立净值（拿起-放回）
        Map<String, SourceNet> sourceNets = new LinkedHashMap<>();
        for (RecognitionEvent e : events) {
            if (e.getSource() == RecognitionSource.MANUAL || e.getProduct() == null) continue;
            String key = e.getSource() + "|" + e.getChannelCode() + "|" + e.getProduct().getSku();
            SourceNet n = sourceNets.computeIfAbsent(key,
                    k -> new SourceNet(e.getSource().name(), e.getChannelCode(),
                            e.getProduct().getSku(), 0, 0));
            if (e.getAction() == DetectAction.PICK) n.pick += e.getQty() == null ? 1 : e.getQty();
            if (e.getAction() == DetectAction.PUT) n.put += e.getQty() == null ? 1 : e.getQty();
        }

        int visionNet = netOf(sourceNets, RecognitionSource.VISION);
        int rfidNet = netOf(sourceNets, RecognitionSource.RFID);
        int weightDelta = session.getWeightDeltaGram() != null ? session.getWeightDeltaGram() : 0;
        boolean weightRecovered = Math.abs(weightDelta) <= weightToleranceGram;

        // 2) 订单声称拿取与重量期望
        int claimedQty = items.stream().mapToInt(OrderItem::getQty).sum();
        int expectedGram = items.stream()
                .mapToInt(i -> i.getQty() * (i.getProduct().getUnitWeightGram() != null
                        ? i.getProduct().getUnitWeightGram() : 0))
                .sum();
        boolean weightMatchesClaim = !weightRecovered
                && Math.abs(Math.abs(weightDelta) - expectedGram) <= weightToleranceGram;

        // 3) 货道库存是否真实减少（批次台账 SALE_OUT）
        int stockOut = 0;
        if (order != null) {
            Set<Long> batchIds = items.stream().map(i -> i.getBatch().getId()).collect(Collectors.toSet());
            for (Long batchId : batchIds) {
                stockOut += ledgerRepository.findByBatchIdOrderByIdAsc(batchId).stream()
                        .filter(l -> l.getLedgerType() == LedgerType.SALE_OUT
                                && Objects.equals(l.getBizId(), order.getId()))
                        .mapToInt(l -> -l.getChangeQty()).sum();
            }
        }

        // 4) 研判
        List<String> reasons = new ArrayList<>();
        reasons.add(String.format("关门净重变化 %dg（容差 ±%dg），%s",
                weightDelta, weightToleranceGram, weightRecovered ? "重量已恢复" : "重量有净减少"));
        reasons.add(String.format("视觉净拿取 %d 件，RFID 净拿取 %d 件，订单计费 %d 件，台账实际出库 %d 件",
                visionNet, rfidNet, claimedQty, stockOut));

        ReviewSuggestion suggestion;
        if (claimedQty == 0) {
            suggestion = ReviewSuggestion.NEED_MANUAL;
            reasons.add("无计费订单或订单未扣款，转人工核实");
        } else if (weightRecovered && visionNet > 0 && rfidNet <= 0) {
            // 典型误扣：拿起查看后放回，重量恢复，仅视觉坚持认为取走
            suggestion = ReviewSuggestion.SUSPECT_MISCHARGE;
            reasons.add("重量已恢复但视觉仍认为商品被取走，且缺乏 RFID 佐证，" +
                    "不能仅凭视觉判定用户购买，建议转人工确认并倾向退款");
        } else if (weightMatchesClaim && stockOut >= claimedQty
                && (rfidNet <= 0 || rfidNet == claimedQty)) {
            suggestion = ReviewSuggestion.EVIDENCE_SUFFICIENT;
            reasons.add("重量净减少与计费商品重量一致，货道库存同步减少" +
                    (rfidNet == claimedQty ? "，RFID 同步佐证" : "")
                    + "，证据充分，建议驳回并向用户展示扣款依据");
        } else {
            suggestion = ReviewSuggestion.NEED_MANUAL;
            reasons.add("重量/视觉/RFID/库存证据相互冲突，转人工确认");
        }
        return new ReviewVerdict(suggestion, reasons, new ArrayList<>(sourceNets.values()),
                weightDelta, weightRecovered, claimedQty, stockOut);
    }

    /** 涉及货道的当前库存快照（复核时点的货道盘点视图） */
    @Transactional(readOnly = true)
    public List<ChannelStockView> channelStockSnapshot(DoorSession session, List<OrderItem> items) {
        Set<String> channelCodes = new LinkedHashSet<>();
        items.forEach(i -> channelCodes.add(i.getChannelCode()));
        if (channelCodes.isEmpty()) {
            eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId())
                    .forEach(e -> { if (e.getChannelCode() != null) channelCodes.add(e.getChannelCode()); });
        }
        List<ChannelStockView> views = new ArrayList<>();
        for (String code : channelCodes) {
            Channel channel = channelRepository
                    .findByCabinetIdAndCode(session.getCabinet().getId(), code).orElse(null);
            if (channel == null) continue;
            List<ChannelStock> stocks = stockRepository.findByChannelId(channel.getId());
            int onHand = stocks.stream().mapToInt(ChannelStock::getQtyOnHand).sum();
            int quarantined = stocks.stream().mapToInt(ChannelStock::getQtyQuarantined).sum();
            views.add(new ChannelStockView(code, channel.getStatus().name(), onHand, quarantined));
        }
        return views;
    }

    /** 该柜机最近一次盘点中涉及货道的盘点行（货道盘点证据） */
    @Transactional(readOnly = true)
    public List<StocktakeItem> recentStocktakeLines(DoorSession session, List<OrderItem> items) {
        Set<String> channelCodes = items.stream().map(OrderItem::getChannelCode)
                .collect(Collectors.toSet());
        List<StocktakeOrder> takes = stocktakeOrderRepository
                .findByCabinetIdOrderByIdDesc(session.getCabinet().getId());
        if (takes.isEmpty()) return List.of();
        StocktakeOrder latest = takes.get(0);
        return stocktakeItemRepository.findByStocktakeId(latest.getId()).stream()
                .filter(i -> channelCodes.isEmpty()
                        || channelCodes.contains(i.getChannel().getCode()))
                .toList();
    }

    private int netOf(Map<String, SourceNet> nets, RecognitionSource source) {
        return nets.values().stream()
                .filter(n -> n.source.equals(source.name()))
                .mapToInt(n -> n.pick - n.put).sum();
    }

    /** 单源单货道单品的拿起/放回 */
    public static class SourceNet {
        public final String source;
        public final String channelCode;
        public final String sku;
        public int pick;
        public int put;

        SourceNet(String source, String channelCode, String sku, int pick, int put) {
            this.source = source;
            this.channelCode = channelCode;
            this.sku = sku;
            this.pick = pick;
            this.put = put;
        }

        public int net() { return pick - put; }
    }

    /** 货道库存快照行 */
    public record ChannelStockView(String channelCode, String channelStatus,
                                   int qtyOnHand, int qtyQuarantined) {}

    /** 研判结论 */
    public record ReviewVerdict(ReviewSuggestion suggestion,
                                List<String> reasons,
                                List<SourceNet> sourceNets,
                                int weightDeltaGram,
                                boolean weightRecovered,
                                int claimedQty,
                                int stockOutQty) {}
}
