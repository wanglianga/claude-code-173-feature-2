package com.cabinet.dto;

import com.cabinet.domain.*;
import com.cabinet.service.ReviewAnalysisService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 误扣款复核证据工作台视图：开门、拿取、重量变化、摄像头识别、
 * 货道盘点、扣款时间与系统研判建议一次聚合，客服不再单看某一个传感器。
 */
public record ReviewEvidenceView(
        Complaint complaint,
        DoorSession session,
        OrderRecord order,
        List<OrderItem> orderItems,
        /** 全量识别事件（重量/视觉/RFID，含视频片段定位与补传标记） */
        List<RecognitionEvent> recognitionEvents,
        /** 系统研判：建议 + 理由 + 各源净值 + 重量是否恢复 */
        ReviewAnalysisService.ReviewVerdict verdict,
        /** 涉及货道当前库存快照（复核时点货道盘点） */
        List<ReviewAnalysisService.ChannelStockView> channelStocks,
        /** 最近一次盘点中涉及货道的盘点行 */
        List<StocktakeItem> recentStocktakeItems,
        /** 扣款时间线 */
        ChargeTiming chargeTiming,
        List<TempLog> tempLogs,
        /** 本投诉单审计轨迹（原始判断与后续修正） */
        List<ReviewAudit> audits) {

    public record ChargeTiming(LocalDateTime chargedAt,
                               String paymentTxnNo,
                               String walletTxnNo,
                               BigDecimal totalAmount,
                               BigDecimal refundedAmount) {}
}
