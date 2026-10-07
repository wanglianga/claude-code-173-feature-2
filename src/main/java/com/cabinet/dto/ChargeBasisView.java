package com.cabinet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户侧扣款依据：证据充分证明已取走时，向用户展示为什么被扣款。
 */
public record ChargeBasisView(
        String orderNo,
        BigDecimal totalAmount,
        LocalDateTime chargedAt,
        String paymentTxnNo,
        LocalDateTime openedAt,
        LocalDateTime closedAt,
        List<ChargeBasisItem> items,
        /** 人话版证据链摘要 */
        List<String> evidenceSummary,
        /** 可回放的视频片段 */
        List<VideoClip> videoClips) {

    public record ChargeBasisItem(String sku, String productName, Integer qty,
                                  BigDecimal unitPrice, BigDecimal subtotal,
                                  String channelCode) {}

    public record VideoClip(String url, Integer startSec, Integer endSec,
                            String source, String channelCode) {}
}
