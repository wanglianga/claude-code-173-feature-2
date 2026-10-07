package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.ChannelHealth;
import com.cabinet.domain.enums.CabinetStatus;
import com.cabinet.domain.enums.MaintenanceType;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 货道维护处置：人工暂停、维护完成恢复、设备换机。
 * 换机不迁移/改写旧柜机上的会话、识别样本与复核结论（原始判断归属旧柜机永久保留），
 * 只在新柜机对应货道记录迁移来源并留痕，形成“原机故障 -> 换机 -> 新机恢复”的完整修正链路。
 */
@Service
@RequiredArgsConstructor
public class ChannelMaintenanceService {

    private final ChannelRepository channelRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelHealthRecordRepository healthRepository;
    private final ChannelMaintenanceLogRepository logRepository;

    @Transactional
    public ChannelMaintenanceLog suspend(String cabinetNo, String channelCode,
                                         String operator, String remark) {
        Channel channel = mustChannel(cabinetNo, channelCode);
        channel.setSuspended(true);
        channelRepository.save(channel);
        healthRepository.findByChannelId(channel.getId()).ifPresent(h -> {
            if (h.getHealth() != ChannelHealth.SUSPENDED) {
                h.setHealth(ChannelHealth.SUSPENDED);
                h.setSuspendedAt(LocalDateTime.now());
                h.setSuspendReason(remark);
                healthRepository.save(h);
            }
        });
        return log(channel, MaintenanceType.SUSPEND, null, null, operator, remark);
    }

    /** 维护完成：解除暂停，健康档案转 MAINTAINED 并开新窗口 */
    @Transactional
    public ChannelMaintenanceLog resume(String cabinetNo, String channelCode,
                                        String operator, String remark) {
        Channel channel = mustChannel(cabinetNo, channelCode);
        channel.setSuspended(false);
        channelRepository.save(channel);
        healthRepository.lockByChannelId(channel.getId()).ifPresent(h -> {
            h.setHealth(ChannelHealth.MAINTAINED);
            h.setWindowStart(LocalDateTime.now());
            h.setMiscountInWindow(0);
            h.setSuspendedAt(null);
            healthRepository.save(h);
        });
        return log(channel, MaintenanceType.MAINTAIN_RESUME, null, null, operator, remark);
    }

    /**
     * 设备换机：旧柜机置离线（保留全部历史会话/证据/复核记录），
     * 新柜机同编码货道记录迁移来源；旧机各货道写 DEVICE_REPLACE 留痕。
     */
    @Transactional
    public List<ChannelMaintenanceLog> replaceCabinet(String oldCabinetNo, String newCabinetNo,
                                                      String operator, String remark) {
        Cabinet oldCab = cabinetRepository.findByCabinetNo(oldCabinetNo)
                .orElseThrow(() -> ApiException.notFound("旧柜机不存在"));
        Cabinet newCab = cabinetRepository.findByCabinetNo(newCabinetNo)
                .orElseThrow(() -> ApiException.notFound("新柜机不存在"));

        oldCab.setStatus(CabinetStatus.OFFLINE);
        cabinetRepository.save(oldCab);

        List<ChannelMaintenanceLog> logs = new ArrayList<>();
        for (Channel oldCh : channelRepository.findByCabinetId(oldCab.getId())) {
            oldCh.setSuspended(true); // 旧机货道随设备下线
            channelRepository.save(oldCh);
            logs.add(log(oldCh, MaintenanceType.DEVICE_REPLACE,
                    newCabinetNo, oldCh.getCode(), operator,
                    "换机至 " + newCabinetNo + " 同编码货道；历史证据保留在本机。" + remark));

            // 新机同编码货道继承迁移来源（没有则创建占位货道，计划商品沿用）
            Channel newCh = channelRepository
                    .findByCabinetIdAndCode(newCab.getId(), oldCh.getCode())
                    .orElseGet(() -> channelRepository.save(Channel.builder()
                            .cabinet(newCab).code(oldCh.getCode())
                            .plannedProduct(oldCh.getPlannedProduct())
                            .capacity(oldCh.getCapacity()).build()));
            newCh.setMigratedFromCabinetNo(oldCabinetNo);
            newCh.setMigratedFromChannelCode(oldCh.getCode());
            channelRepository.save(newCh);
        }
        return logs;
    }

    @Transactional(readOnly = true)
    public List<ChannelMaintenanceLog> history(String cabinetNo, String channelCode) {
        Channel channel = mustChannel(cabinetNo, channelCode);
        return logRepository.findByChannelIdOrderByIdDesc(channel.getId());
    }

    private Channel mustChannel(String cabinetNo, String code) {
        Cabinet cabinet = cabinetRepository.findByCabinetNo(cabinetNo)
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        return channelRepository.findByCabinetIdAndCode(cabinet.getId(), code)
                .orElseThrow(() -> ApiException.notFound("货道不存在: " + code));
    }

    private ChannelMaintenanceLog log(Channel channel, MaintenanceType type,
                                      String targetCabinetNo, String targetChannelCode,
                                      String operator, String remark) {
        return logRepository.save(ChannelMaintenanceLog.builder()
                .channel(channel).cabinetNo(channel.getCabinet().getCabinetNo())
                .type(type).targetCabinetNo(targetCabinetNo).targetChannelCode(targetChannelCode)
                .operator(operator).remark(remark).actionAt(LocalDateTime.now()).build());
    }
}
