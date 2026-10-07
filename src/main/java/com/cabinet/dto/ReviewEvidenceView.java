package com.cabinet.dto;

import java.util.List;

/**
 * 误扣款复核证据聚合视图：把一次开门的全部判断依据放在一起，
 * 客服不再只看单一传感器。
 * - 开门/关门/扣款时间；
 * - 重量净变化与“重量是否恢复”；
 * - 各识别源（重量/视觉/RFID）独立的拿起-放回净值与最低置信度；
 * - 订单扣款明细；
 * - 货道盘点快照（系统数/实盘数/差异）；
 * - 设备离线后补传事件。
 */
public record ReviewEvidenceView(
        String sessionNo,
        String cabinetNo,
        String focusChannelCode,
        String openedAt,
        String closedAt,
        String chargedAt,
        Long doorToChargeMillis,
        Integer weightDeltaGram,
        boolean weightRestored,
        String orderStatus,
        List<SourceNetView> sourceNets,
        List<String> conflictReasons,
        List<StocktakeLineView> stocktakeLines,
        List<LateEventView> lateEvents,
        String verdict,
        String verdictReason) {

    public record SourceNetView(String source, String channelCode, String sku,
                                int pick, int put, int net, double minConfidence) {}

    public record StocktakeLineView(String stocktakeNo, String stocktakeStatus,
                                    String channelCode, String batchNo,
                                    Integer systemQty, Integer countedQty, Integer diffQty,
                                    String startedAt) {}

    public record LateEventView(String source, String action, String channelCode,
                                String sku, Integer qty, String detectedAt, String note) {}
}
