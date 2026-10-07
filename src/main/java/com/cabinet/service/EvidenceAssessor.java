package com.cabinet.service;

import com.cabinet.domain.OrderItem;
import com.cabinet.domain.OrderRecord;
import com.cabinet.domain.RecognitionEvent;
import com.cabinet.domain.DoorSession;
import com.cabinet.domain.enums.DetectAction;
import com.cabinet.domain.enums.OrderStatus;
import com.cabinet.domain.enums.RecognitionSource;
import com.cabinet.domain.enums.ReviewVerdict;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 误扣款证据评估器（纯领域逻辑，不落库）：
 * 将重量、视觉、RFID 各自的拿起/放回净值、柜机净重变化、货道盘点差异放在一起交叉比对：
 * - 重量已恢复（净重变化≈0）但视觉仍判定商品被取走，或视觉净值为 0 却已扣款：
 *   典型误判，给出退款建议，但仍必须转人工确认（系统不自动退款）；
 * - 多源结论互相矛盾（数量不一致、重量与视觉方向相反）：CONFLICT，转人工；
 * - 多源一致、称重匹配且无盘盈反证：CONFIRM_SUGGESTED，向用户展示扣款依据；
 * - 订单未扣款：NO_CHARGE。
 */
@Component
public class EvidenceAssessor {

    public record SourceCount(String source, String channelCode, String sku,
                              int pick, int put, int net, double minConfidence) {}

    public record Assessment(ReviewVerdict verdict, List<String> reasons,
                             List<SourceCount> sourceCounts, boolean weightRestored,
                             boolean stocktakeSurplus) {}

    public Assessment assess(DoorSession session, OrderRecord order, List<OrderItem> items,
                             List<RecognitionEvent> events, Integer weightToleranceGram,
                             boolean stocktakeSurplus) {
        List<String> reasons = new ArrayList<>();

        if (order == null || order.getStatus() == OrderStatus.PENDING_REVIEW
                || order.getStatus() == OrderStatus.CANCELLED) {
            reasons.add("订单未实际扣款（" + (order == null ? "无订单" : order.getStatus()) + "），无款可退");
            return new Assessment(ReviewVerdict.NO_CHARGE, reasons,
                    countBySource(events), true, stocktakeSurplus);
        }

        List<SourceCount> counts = countBySource(events);
        int delta = session.getWeightDeltaGram() == null ? 0 : session.getWeightDeltaGram();
        int tol = weightToleranceGram == null ? 80 : weightToleranceGram;
        boolean weightRestored = Math.abs(delta) <= tol;

        int visionNet = sumNet(counts, "VISION");
        int weightNet = sumNet(counts, "WEIGHT");
        int rfidNet = sumNet(counts, "RFID");

        boolean visionSaysTaken = visionNet > 0;
        boolean anySensorTaken = visionNet > 0 || weightNet > 0 || rfidNet > 0;

        // 1) 重量已恢复但视觉仍认为取走 -> 题目明确：不能直接判定购买，转人工（建议退款）
        if (weightRestored && visionSaysTaken) {
            reasons.add(String.format(
                    "柜机净重变化 %dg 在容差 %dg 内（重量已恢复），但视觉仍判定净拿取 %d 件，"
                    + "符合“拿起查看后放回被误判”特征，不得直接判定购买，转人工确认",
                    delta, tol, visionNet));
            return new Assessment(ReviewVerdict.REFUND_SUGGESTED, reasons, counts,
                    true, stocktakeSurplus);
        }

        // 2) 视觉自身净值为 0（拿起放回抵消）却产生扣款
        if (visionNet == 0 && weightNet <= 0 && order.getStatus() == OrderStatus.CHARGED
                && events.stream().anyMatch(e -> e.getSource() == RecognitionSource.VISION)) {
            reasons.add("视觉识别拿起-放回净值为 0（用户已放回），订单却已扣款，疑似误判");
            return new Assessment(ReviewVerdict.REFUND_SUGGESTED, reasons, counts,
                    weightRestored, stocktakeSurplus);
        }

        // 3) 盘点盘盈（实物多于系统数）反证商品仍在货道
        if (stocktakeSurplus && anySensorTaken) {
            reasons.add("货道盘点出现盘盈（实物多于系统库存），反证被识别拿走的商品仍在货道");
            return new Assessment(ReviewVerdict.REFUND_SUGGESTED, reasons, counts,
                    weightRestored, stocktakeSurplus);
        }

        // 4) 各传感器数量不一致或方向矛盾 -> 冲突转人工
        List<Integer> positiveNets = new ArrayList<>();
        if (visionNet > 0) positiveNets.add(visionNet);
        if (weightNet > 0) positiveNets.add(weightNet);
        if (rfidNet > 0) positiveNets.add(rfidNet);
        if (positiveNets.stream().distinct().count() > 1) {
            reasons.add(String.format("多源净拿取数量不一致 VISION=%d WEIGHT=%d RFID=%d，证据冲突",
                    visionNet, weightNet, rfidNet));
            return new Assessment(ReviewVerdict.CONFLICT, reasons, counts,
                    weightRestored, stocktakeSurplus);
        }
        if (weightNet < 0 || (weightRestored && anySensorTaken && (weightNet > 0 || rfidNet > 0)
                && !visionSaysTaken)) {
            // 重量显示放回、但 RFID 等仍认为取走
            reasons.add("称重信号显示商品已放回，其余传感器仍判定取走，证据方向矛盾");
            return new Assessment(ReviewVerdict.CONFLICT, reasons, counts,
                    weightRestored, stocktakeSurplus);
        }

        // 5) 称重交叉校验：订单扣款件数总重 vs 净重变化
        int chargedQty = items.stream().mapToInt(OrderItem::getQty).sum();
        int expectedGram = items.stream()
                .mapToInt(i -> i.getQty() * (i.getProduct() != null
                        && i.getProduct().getUnitWeightGram() != null
                        ? i.getProduct().getUnitWeightGram() : 0)).sum();
        if (expectedGram > 0 && Math.abs(Math.abs(delta) - expectedGram) > tol) {
            reasons.add(String.format(
                    "扣款 %d 件理论重 %dg 与柜机净重变化 %dg 超容差 %dg，称重交叉校验不符",
                    chargedQty, expectedGram, delta, tol));
            return new Assessment(ReviewVerdict.CONFLICT, reasons, counts,
                    weightRestored, stocktakeSurplus);
        }

        reasons.add(String.format(
                "多源证据一致（净拿取 %d 件）、称重匹配（%dg）、无盘盈反证，扣款依据充分",
                chargedQty, delta));
        return new Assessment(ReviewVerdict.CONFIRM_SUGGESTED, reasons, counts,
                weightRestored, stocktakeSurplus);
    }

    private List<SourceCount> countBySource(List<RecognitionEvent> events) {
        Map<String, int[]> agg = new LinkedHashMap<>(); // pick,put
        Map<String, Double> conf = new HashMap<>();
        Map<String, String> meta = new HashMap<>();
        for (RecognitionEvent e : events) {
            if (e.getProduct() == null) continue;
            String key = e.getSource().name() + "|" +
                    (e.getChannelCode() == null ? "" : e.getChannelCode());
            int[] pp = agg.computeIfAbsent(key, k -> new int[2]);
            int q = e.getQty() == null ? 1 : e.getQty();
            if (e.getAction() == DetectAction.PICK) pp[0] += q;
            else if (e.getAction() == DetectAction.PUT) pp[1] += q;
            conf.merge(key, e.getConfidence() == null ? 1.0 : e.getConfidence(), Math::min);
            meta.putIfAbsent(key, (e.getChannelCode() == null ? "" : e.getChannelCode())
                    + "|" + e.getProduct().getSku());
        }
        List<SourceCount> result = new ArrayList<>();
        for (var en : agg.entrySet()) {
            String[] k = en.getKey().split("\\|", 2);
            String[] cm = meta.getOrDefault(en.getKey(), "|").split("\\|", 2);
            int[] pp = en.getValue();
            result.add(new SourceCount(k[0], cm[0], cm.length > 1 ? cm[1] : "",
                    pp[0], pp[1], pp[0] - pp[1], conf.get(en.getKey())));
        }
        result.sort(Comparator.comparing(SourceCount::source));
        return result;
    }

    private int sumNet(List<SourceCount> counts, String source) {
        return counts.stream().filter(c -> c.source().equals(source))
                .mapToInt(SourceCount::net).sum();
    }
}
