package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 货道健康监控：区分「单次误识别」与「设备故障」。
 * 同一货道在统计窗口内反复出现误扣款退款投诉：
 * - 达到预警次数：WARN 告警，提示关注（仍按单次误识别处理）；
 * - 达到故障阈值：判定疑似设备故障 —— 暂停货道（禁止上架）、
 *   自动生成检查工单并派维护，CRITICAL 告警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelHealthService {

    private final ComplaintRepository complaintRepository;
    private final ChannelRepository channelRepository;
    private final MaintenanceOrderRepository maintenanceRepository;
    private final AlertService alertService;
    private final MaintenanceService maintenanceService;
    private final ReviewAuditService auditService;

    @Value("${cabinet.review.channel-complaint-warn:2}")
    private int warnThreshold;

    @Value("${cabinet.review.channel-complaint-threshold:3}")
    private int faultThreshold;

    @Value("${cabinet.review.channel-complaint-window-days:7}")
    private int windowDays;

    /**
     * 退款成立归责到货道后调用。返回该货道窗口内累计误扣退款投诉数。
     */
    @Transactional
    public long onRefundAttributed(Cabinet cabinet, String channelCode, Complaint complaint) {
        Channel channel = channelRepository
                .findByCabinetIdAndCode(cabinet.getId(), channelCode).orElse(null);
        if (channel == null) return 0;
        long count = complaintRepository.countRefundedByChannelSince(
                cabinet.getId(), channelCode,
                LocalDateTime.now().minusDays(windowDays));
        String ref = cabinet.getCabinetNo() + "/" + channelCode;

        if (count >= faultThreshold && channel.getStatus() != ChannelStatus.SUSPENDED) {
            // 疑似设备故障：暂停货道 + 自动工单 + CRITICAL 告警
            channel.setStatus(ChannelStatus.SUSPENDED);
            channelRepository.save(channel);
            MaintenanceOrder mo = maintenanceService.createAuto(cabinet, channel,
                    MaintenanceType.INSPECT,
                    String.format("%d 天内 %d 次误扣款退款投诉，疑似设备故障", windowDays, count));
            alertService.raise(cabinet, AlertType.CHANNEL_HEALTH, "CRITICAL",
                    String.format("货道 %s %d 天内 %d 次误扣款投诉，已暂停并派维护工单 %s",
                            channelCode, windowDays, count, mo.getMoNo()),
                    "CHANNEL", channel.getId());
            auditService.log("CHANNEL", ref, "CHANNEL_SUSPENDED", "system",
                    String.format("窗口内误扣款退款投诉 %d 次 >= 阈值 %d，判定疑似设备故障，"
                            + "货道暂停，自动工单 %s；与单次误识别区分处理",
                            count, faultThreshold, mo.getMoNo()));
        } else if (count >= warnThreshold && channel.getStatus() != ChannelStatus.SUSPENDED) {
            alertService.raise(cabinet, AlertType.CHANNEL_HEALTH, "WARN",
                    String.format("货道 %s %d 天内 %d 次误扣款投诉，关注中（未达故障阈值 %d）",
                            channelCode, windowDays, count, faultThreshold),
                    "CHANNEL", channel.getId());
        } else {
            auditService.log("CHANNEL", ref, "SINGLE_MISJUDGE", "system",
                    String.format("投诉 %s 退款归责本货道，窗口内累计 %d 次，按单次误识别处理",
                            complaint.getComplaintNo(), count));
        }
        return count;
    }

    /** 货道健康视图 */
    @Transactional(readOnly = true)
    public List<ChannelHealthView> healthView() {
        return channelRepository.findAll().stream().map(ch -> {
            long count = complaintRepository.countRefundedByChannelSince(
                    ch.getCabinet().getId(), ch.getCode(),
                    LocalDateTime.now().minusDays(windowDays));
            List<MaintenanceOrder> open = maintenanceRepository.findByChannelIdAndStatusIn(
                    ch.getId(), List.of(MaintenanceStatus.OPEN, MaintenanceStatus.IN_PROGRESS));
            return new ChannelHealthView(
                    ch.getCabinet().getCabinetNo(), ch.getCode(),
                    ch.getStatus().name(), ch.getEquipmentSn(),
                    count, warnThreshold, faultThreshold,
                    open.stream().map(MaintenanceOrder::getMoNo).toList());
        }).toList();
    }

    @Transactional
    public Channel suspend(Long channelId, String operator, String reason) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> com.cabinet.exception.ApiException.notFound("货道不存在"));
        channel.setStatus(ChannelStatus.SUSPENDED);
        Channel saved = channelRepository.save(channel);
        auditService.log("CHANNEL", channel.getCabinet().getCabinetNo() + "/" + channel.getCode(),
                "CHANNEL_SUSPENDED", operator, "人工暂停: " + reason);
        return saved;
    }

    @Transactional
    public Channel resume(Long channelId, String operator, String note) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> com.cabinet.exception.ApiException.notFound("货道不存在"));
        channel.setStatus(ChannelStatus.NORMAL);
        Channel saved = channelRepository.save(channel);
        auditService.log("CHANNEL", channel.getCabinet().getCabinetNo() + "/" + channel.getCode(),
                "CHANNEL_RESUMED", operator, "恢复售卖: " + note);
        return saved;
    }

    public record ChannelHealthView(String cabinetNo, String channelCode, String status,
                                    String equipmentSn, long refundedComplaintsInWindow,
                                    int warnThreshold, int faultThreshold,
                                    List<String> openMaintenanceNos) {}
}
