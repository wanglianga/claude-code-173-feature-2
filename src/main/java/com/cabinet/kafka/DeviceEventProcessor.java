package com.cabinet.kafka;

import com.cabinet.domain.Cabinet;
import com.cabinet.domain.DeviceEventLog;
import com.cabinet.domain.enums.AlertType;
import com.cabinet.kafka.dto.DeviceEvent;
import com.cabinet.repo.CabinetRepository;
import com.cabinet.repo.DeviceEventLogRepository;
import com.cabinet.service.AlertService;
import com.cabinet.service.SessionSettlementService;
import com.cabinet.service.TemperatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 设备事件处理器：Kafka 消费端与 HTTP 模拟入口共用同一套业务逻辑。
 * 幂等去重：设备侧 eventId 唯一约束，重复投递/离线补传只处理一次；
 * 补传事件（backfill 标记或事件时间明显滞后）记录留痕，迟到识别事件仅存档。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceEventProcessor {

    /** 事件时间早于当前超过该分钟数即视为补传 */
    private static final int BACKFILL_MINUTES = 30;

    private final CabinetRepository cabinetRepository;
    private final SessionSettlementService sessionService;
    private final TemperatureService temperatureService;
    private final AlertService alertService;
    private final DeviceEventLogRepository eventLogRepository;

    @Transactional
    public void process(DeviceEvent event) {
        // 1) 幂等去重：同一 eventId 只处理一次（设备重试/补传重复不二次生效）
        String eventId = event.getEventId();
        if (eventId != null && !eventId.isBlank()) {
            var dup = eventLogRepository.findByEventId(eventId);
            if (dup.isPresent()) {
                DeviceEventLog log1 = dup.get();
                log1.setDupCount(log1.getDupCount() + 1);
                eventLogRepository.save(log1);
                log.info("重复设备事件 {} ({}) 已忽略，累计重复 {} 次",
                        eventId, event.getType(), log1.getDupCount());
                return;
            }
        }
        boolean backfill = Boolean.TRUE.equals(event.getBackfill())
                || (event.getEventTime() != null
                    && event.getEventTime().isBefore(LocalDateTime.now().minusMinutes(BACKFILL_MINUTES)));
        eventLogRepository.save(DeviceEventLog.builder()
                .eventId(eventId != null && !eventId.isBlank()
                        ? eventId : "AUTO-" + UUID.randomUUID())
                .type(event.getType())
                .cabinetNo(event.getCabinetNo())
                .sessionNo(event.getSessionNo())
                .eventTime(event.getEventTime())
                .receivedAt(LocalDateTime.now())
                .backfill(backfill)
                .dupCount(0)
                .build());

        // 2) 业务分发
        LocalDateTime at = event.getEventTime() != null ? event.getEventTime() : LocalDateTime.now();
        switch (event.getType()) {
            case "DOOR_OPEN" -> sessionService.openDoor(
                    event.getCabinetNo(), event.getSessionNo(),
                    event.getUserCode(), event.getUserName(), at);
            case "RECOGNITION" -> sessionService.recordRecognition(
                    event.getSessionNo(), event.getCabinetNo(), event.getChannelCode(),
                    event.getSku(), event.getBatchNo(), event.getSource(), event.getAction(),
                    event.getQty(), event.getConfidence(), event.getWeightGram(),
                    event.getVideoClipUrl(), event.getClipStartSec(), event.getClipEndSec(),
                    at, null, event.getEventId(), backfill);
            case "DOOR_CLOSE" -> sessionService.closeDoor(
                    event.getSessionNo(), event.getWeightDeltaGram(), at);
            case "TEMP" -> {
                Cabinet cabinet = cabinetRepository.findByCabinetNo(event.getCabinetNo()).orElse(null);
                if (cabinet != null && event.getTempC() != null) {
                    temperatureService.record(cabinet, event.getTempC(), at);
                } else {
                    log.warn("温度事件柜机不存在: {}", event.getCabinetNo());
                }
            }
            case "HEARTBEAT" -> cabinetRepository.findByCabinetNo(event.getCabinetNo())
                    .ifPresent(c -> {
                        c.setLastHeartbeatAt(at);
                        if (event.getTempC() != null) c.setLastTempC(event.getTempC());
                        cabinetRepository.save(c);
                    });
            case "CHANNEL_JAM" -> cabinetRepository.findByCabinetNo(event.getCabinetNo())
                    .ifPresent(c -> alertService.raise(c, AlertType.CHANNEL_JAM, "WARN",
                            "货道 " + event.getJamChannelCode() + " 卡货", "CHANNEL_JAM", null));
            default -> log.warn("未知设备事件类型: {}", event.getType());
        }
    }
}
