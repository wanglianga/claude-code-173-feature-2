package com.cabinet.service;

import com.cabinet.domain.Product;
import com.cabinet.domain.ProductBatch;
import com.cabinet.domain.RecognitionEvent;
import com.cabinet.domain.enums.DetectAction;
import com.cabinet.domain.enums.RecognitionSource;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 识别融合：一次开门内重量/视觉/RFID 多源事件 -> 净拿取行。
 * 规则：
 * 1) 每个数据源独立计算 (货道+商品) 的净拿取（拿起-放回），天然抵消"拿起又放回"；
 * 2) 多源数量一致 -> 采纳，置信度取各源最低；不一致 -> 识别冲突，采纳最高置信度源结果，
 *    并将整体置信度压到阈值以下，触发人工复核（不扣款）；
 * 3) 用单件重量与柜机净重变化做交叉校验，超容差同样压低置信度；
 * 4) RFID 可给出批次提示，最终扣减仍按货道 FEFO。
 */
@Component
public class RecognitionFusion {

    public static final double CONFLICT_CONFIDENCE = 0.50;

    public List<NetLine> fuse(List<RecognitionEvent> events,
                              int weightDeltaGram,
                              int weightToleranceGram) {
        // 按数据源分组统计
        Map<RecognitionSource, Map<String, SourceAgg>> perSource = new EnumMap<>(RecognitionSource.class);
        for (RecognitionEvent e : events) {
            if (e.getSource() == RecognitionSource.MANUAL || e.getProduct() == null) continue;
            String key = e.getChannelCode() + "|" + e.getProduct().getId();
            SourceAgg agg = perSource.computeIfAbsent(e.getSource(), k -> new HashMap<>())
                    .computeIfAbsent(key, k -> new SourceAgg(e));
            agg.merge(e);
        }

        // 以 (channel,product) 归并各源的净值
        Map<String, List<SourceNet>> byKey = new LinkedHashMap<>();
        for (var srcEntry : perSource.entrySet()) {
            for (var aggEntry : srcEntry.getValue().entrySet()) {
                SourceAgg a = aggEntry.getValue();
                int net = a.pick - a.put;
                if (net <= 0) continue;
                byKey.computeIfAbsent(aggEntry.getKey(), k -> new ArrayList<>())
                        .add(new SourceNet(a.product, srcEntry.getKey(), net, a.minConfidence, a.lastBatch));
            }
        }

        List<NetLine> lines = new ArrayList<>();
        for (var entry : byKey.entrySet()) {
            List<SourceNet> nets = entry.getValue();
            String[] parts = entry.getKey().split("\\|", 2);
            String channelCode = parts[0];

            boolean conflict = nets.stream().map(n -> n.qty).distinct().count() > 1;
            SourceNet chosen;
            double confidence;
            if (conflict) {
                chosen = nets.stream().max(Comparator.comparingDouble(n -> n.confidence)).get();
                confidence = CONFLICT_CONFIDENCE;
            } else {
                chosen = nets.get(0);
                confidence = nets.stream().mapToDouble(n -> n.confidence).min().orElse(0);
            }
            Set<String> sources = new LinkedHashSet<>();
            nets.forEach(n -> sources.add(n.source.name()));
            lines.add(new NetLine(channelCode, chosen.product, chosen.batchHint, chosen.qty,
                    confidence, conflict, sources));
        }

        // 称重交叉校验：净拿取总重 vs 柜机重量净变化。
        // delta=0 也必须校验：重量已恢复但识别仍认为商品被取走（拿起查看后放回），
        // 属于典型矛盾场景，不能直接判定用户购买，压低置信度转人工确认。
        boolean hasWeightData = lines.stream()
                .anyMatch(l -> l.product.getUnitWeightGram() != null);
        if (hasWeightData && !lines.isEmpty()) {
            int expected = lines.stream()
                    .mapToInt(l -> l.qty * (l.product.getUnitWeightGram() != null
                            ? l.product.getUnitWeightGram() : 0))
                    .sum();
            if (Math.abs(Math.abs(weightDeltaGram) - expected) > weightToleranceGram) {
                for (int i = 0; i < lines.size(); i++) {
                    NetLine l = lines.get(i);
                    lines.set(i, new NetLine(l.channelCode, l.product, l.batchHint, l.qty,
                            Math.min(l.minConfidence, CONFLICT_CONFIDENCE), true, l.sources));
                }
            }
        }
        return lines;
    }

    private static class SourceAgg {
        Product product;
        int pick;
        int put;
        double minConfidence = 1.0;
        ProductBatch lastBatch;

        SourceAgg(RecognitionEvent e) {
            this.product = e.getProduct();
        }

        void merge(RecognitionEvent e) {
            int q = e.getQty() == null ? 1 : e.getQty();
            if (e.getAction() == DetectAction.PICK) pick += q;
            else if (e.getAction() == DetectAction.PUT) put += q;
            if (e.getConfidence() != null) minConfidence = Math.min(minConfidence, e.getConfidence());
            if (e.getBatch() != null) lastBatch = e.getBatch();
            this.product = e.getProduct();
        }
    }

    private record SourceNet(Product product, RecognitionSource source, int qty,
                             double confidence, ProductBatch batchHint) {}

    /** 融合后的净拿取行 */
    public record NetLine(String channelCode, Product product, ProductBatch batchHint,
                          int qty, double minConfidence, boolean conflict, Set<String> sources) {}
}
