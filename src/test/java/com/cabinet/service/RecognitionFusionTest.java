package com.cabinet.service;

import com.cabinet.domain.Product;
import com.cabinet.domain.ProductBatch;
import com.cabinet.domain.RecognitionEvent;
import com.cabinet.domain.enums.DetectAction;
import com.cabinet.domain.enums.RecognitionSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecognitionFusionTest {

    private final RecognitionFusion fusion = new RecognitionFusion();
    private final LocalDateTime t = LocalDateTime.now();

    private RecognitionEvent evt(Product p, RecognitionSource src, DetectAction act,
                                 int qty, double conf, String channel, Integer weight) {
        return RecognitionEvent.builder()
                .product(p).source(src).action(act).qty(qty).confidence(conf)
                .channelCode(channel).weightGram(weight).detectedAt(t).build();
    }

    private Product product(int unitWeight) {
        Product p = Product.builder().sku("P1").name("牛奶").unitWeightGram(unitWeight).build();
        p.setId(1L);
        return p;
    }

    @Test
    void 拿起又放回应抵消为零() {
        Product p = product(260);
        List<RecognitionEvent> events = List.of(
                evt(p, RecognitionSource.VISION, DetectAction.PICK, 1, 0.95, "A01", null),
                evt(p, RecognitionSource.VISION, DetectAction.PUT, 1, 0.93, "A01", null));
        var lines = fusion.fuse(events, 0, 80);
        assertThat(lines).isEmpty();
    }

    @Test
    void 多源一致应融合成功() {
        Product p = product(260);
        List<RecognitionEvent> events = List.of(
                evt(p, RecognitionSource.WEIGHT, DetectAction.PICK, 2, 0.90, "A01", 520),
                evt(p, RecognitionSource.VISION, DetectAction.PICK, 2, 0.88, "A01", null),
                evt(p, RecognitionSource.RFID, DetectAction.PICK, 2, 0.99, "A01", null));
        var lines = fusion.fuse(events, 520, 80);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).qty()).isEqualTo(2);
        assertThat(lines.get(0).minConfidence()).isEqualTo(0.88);
        assertThat(lines.get(0).conflict()).isFalse();
    }

    @Test
    void 多源数量冲突应压低置信度并标记() {
        Product p = product(260);
        List<RecognitionEvent> events = List.of(
                evt(p, RecognitionSource.WEIGHT, DetectAction.PICK, 2, 0.90, "A01", 520),
                evt(p, RecognitionSource.VISION, DetectAction.PICK, 1, 0.80, "A01", null));
        var lines = fusion.fuse(events, 260, 80);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).conflict()).isTrue();
        assertThat(lines.get(0).qty()).isEqualTo(2); // 采纳高置信源
        assertThat(lines.get(0).minConfidence()).isLessThan(0.75);
    }

    @Test
    void 称重交叉校验超容差应触发存疑() {
        Product p = product(260);
        List<RecognitionEvent> events = new ArrayList<>(List.of(
                evt(p, RecognitionSource.WEIGHT, DetectAction.PICK, 1, 0.99, "A01", 260),
                evt(p, RecognitionSource.VISION, DetectAction.PICK, 1, 0.99, "A01", null)));
        // 柜机净变化 900g，但只识别拿走 1 件 260g，超 80g 容差
        var lines = fusion.fuse(events, 900, 80);
        assertThat(lines.get(0).conflict()).isTrue();
        assertThat(lines.get(0).minConfidence()).isLessThan(0.75);
    }

    @Test
    void 重量已恢复但视觉仍认为取走应压置信度转人工() {
        Product p = product(260);
        List<RecognitionEvent> events = new ArrayList<>(List.of(
                evt(p, RecognitionSource.VISION, DetectAction.PICK, 1, 0.95, "A01", null)));
        // 拿起查看后放回：关门净重 0，但视觉净拿取 1 件 -> 矛盾，不能直接判定购买
        var lines = fusion.fuse(events, 0, 80);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).conflict()).isTrue();
        assertThat(lines.get(0).minConfidence()).isLessThan(0.75);
    }
}
