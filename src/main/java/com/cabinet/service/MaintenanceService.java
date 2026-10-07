package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 维护工单：货道检查/维修/换机。
 * 换机（REPLACE）完成时记录新旧设备序列号，货道历史投诉、识别、订单记录全部保留，
 * 仅设备标识更替，满足「货道换机后原始判断与修正过程可追溯」。
 */
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private final MaintenanceOrderRepository maintenanceRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;
    private final ReviewAuditService auditService;

    /** 系统自动建单（货道健康触发） */
    @Transactional
    public MaintenanceOrder createAuto(Cabinet cabinet, Channel channel,
                                       MaintenanceType type, String reason) {
        return create(cabinet, channel, type, reason, true, "system");
    }

    /** 人工建单 */
    @Transactional
    public MaintenanceOrder createManual(String cabinetNo, String channelCode,
                                         MaintenanceType type, String reason, String operator) {
        Cabinet cabinet = cabinetRepository.findByCabinetNo(cabinetNo)
                .orElseThrow(() -> ApiException.notFound("柜机不存在: " + cabinetNo));
        Channel channel = channelRepository.findByCabinetIdAndCode(cabinet.getId(), channelCode)
                .orElseThrow(() -> ApiException.notFound("货道不存在: " + channelCode));
        return create(cabinet, channel, type, reason, false, operator);
    }

    private MaintenanceOrder create(Cabinet cabinet, Channel channel, MaintenanceType type,
                                    String reason, boolean auto, String actor) {
        MaintenanceOrder mo = maintenanceRepository.save(MaintenanceOrder.builder()
                .moNo(genNo())
                .cabinet(cabinet).channel(channel)
                .type(type).reason(reason)
                .autoCreated(auto)
                .status(MaintenanceStatus.OPEN)
                .build());
        auditService.log("CHANNEL", cabinet.getCabinetNo() + "/" + channel.getCode(),
                "MAINTENANCE_CREATED", actor,
                (auto ? "系统自动" : "人工") + "创建" + type + "工单 " + mo.getMoNo() + ": " + reason);
        return mo;
    }

    @Transactional
    public MaintenanceOrder start(String moNo, String operator) {
        MaintenanceOrder mo = byNo(moNo);
        if (mo.getStatus() != MaintenanceStatus.OPEN) {
            throw ApiException.conflict("工单不在待处理状态");
        }
        mo.setStatus(MaintenanceStatus.IN_PROGRESS);
        mo.setOperatorName(operator);
        mo.setStartedAt(LocalDateTime.now());
        auditService.log("CHANNEL", refOf(mo), "MAINTENANCE_STARTED", operator, "工单开始处理");
        return maintenanceRepository.save(mo);
    }

    /**
     * 完成工单。REPLACE 必须提供新设备序列号（换机留痕：旧 SN 存档，货道换新 SN）。
     * 完成后货道如处于暂停态则恢复售卖。
     */
    @Transactional
    public MaintenanceOrder complete(String moNo, String operator,
                                     String resolution, String newEquipmentSn) {
        MaintenanceOrder mo = byNo(moNo);
        if (mo.getStatus() == MaintenanceStatus.DONE) {
            return mo; // 幂等：重复完成直接返回
        }
        Channel channel = mo.getChannel();
        if (mo.getType() == MaintenanceType.REPLACE) {
            if (newEquipmentSn == null || newEquipmentSn.isBlank()) {
                throw ApiException.badRequest("换机工单必须填写新设备序列号");
            }
            mo.setOldEquipmentSn(channel.getEquipmentSn());
            mo.setNewEquipmentSn(newEquipmentSn);
            channel.setEquipmentSn(newEquipmentSn);
        }
        mo.setStatus(MaintenanceStatus.DONE);
        mo.setResolution(resolution);
        mo.setOperatorName(operator);
        mo.setFinishedAt(LocalDateTime.now());
        if (channel.getStatus() == ChannelStatus.SUSPENDED) {
            channel.setStatus(ChannelStatus.NORMAL);
        }
        channelRepository.save(channel);
        MaintenanceOrder saved = maintenanceRepository.save(mo);
        auditService.log("CHANNEL", refOf(mo), "MAINTENANCE_COMPLETED", operator,
                String.format("工单完成(%s): %s%s；货道恢复售卖，历史投诉/识别/订单记录保留",
                        mo.getType(), resolution == null ? "" : resolution,
                        mo.getType() == MaintenanceType.REPLACE
                                ? String.format("；换机 %s -> %s", mo.getOldEquipmentSn(), newEquipmentSn)
                                : ""));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<MaintenanceOrder> list(MaintenanceStatus status) {
        return status == null ? maintenanceRepository.findAll()
                : maintenanceRepository.findByStatus(status);
    }

    private MaintenanceOrder byNo(String moNo) {
        return maintenanceRepository.findByMoNo(moNo)
                .orElseThrow(() -> ApiException.notFound("维护工单不存在: " + moNo));
    }

    private String refOf(MaintenanceOrder mo) {
        return mo.getCabinet().getCabinetNo() + "/" + mo.getChannel().getCode();
    }

    private String genNo() {
        return "MO" + LocalDate.now().toString().replace("-", "")
                + String.format("%04d", System.nanoTime() % 10000);
    }
}
