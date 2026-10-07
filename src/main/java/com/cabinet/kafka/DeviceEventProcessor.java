package com.cabinet.kafka;

import com.cabinet.domain.Cabinet;
import com.cabinet.domain.DeviceEventDedup;
import com.cabinet.domain.DoorSession;
import com.cabinet.domain.RecognitionEvent;
import com.cabinet.domain.ReviewCase;
import com.cabinet.domain.enums.AlertType;
import com.cabinet.domain.enums.SessionStatus;
import com.cabinet.kafka.dto.DeviceEvent;
import com.cabinet.repo.CabinetRepository;
import com.cabinet.repo.DeviceEventDedupRepository;
import com.cabinet.repo.DoorSessionRepository;
import com.cabinet.repo.RecognitionEventRepository;
import com.cabinet.repo.ReviewCaseRepository;
import com.cabinet.service.AlertService;
import com.cabinet.service.ReviewCaseService;
import com.cabinet.service.SessionSettlementService;
import com.cabinet.service.TemperatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 设备事件处理器：Kafka 消费端与 HTTP 模拟入口共用同一套业务逻辑。
 * 入口先做幂等去重（设备 eventId 或内容指纹）：
 * 网络重试、设备离线恢复后集中补传的重复事件只会生效一次，杜绝重复识别/重复扣款。
 * 关门结算后才到达的识别事件标记为“补传”，仅作复核参考，不改变原始结算与证据快照。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceEventProcessor {

    private final CabinetRepository cabinetRepository;
    private final SessionSettlementService sessionService;
    private final TemperatureService temperatureService;
    private final AlertService alertService;
    private final DeviceEventDedupRepository dedupRepository;
    private final DoorSessionRepository sessionRepository;
    private final RecognitionEventRepository eventRepository;
    private final ReviewCaseRepository reviewRepository;
    private final ReviewCaseService reviewCaseService;

    /** 处理结果，便于联调观察是否命中重复/补传 */
    public record ProcessResult(String status, String eventKey, boolean lateArrival) {}

    @Transactional
    public ProcessResult processDetailed(DeviceEvent event) {
        LocalDateTime at = event.getEventTime() != null ? event.getEventTime() : LocalDateTime.now();
        String key = eventKey(event, at);

        DeviceEventDedup dedup = dedupRepository.findByEventKey(key).orElse(null);
        if (dedup != null) {
            dedup.setDuplicateCount(dedup.getDuplicateCount() + 1);
            dedup.setLastDuplicateAt(LocalDateTime.now());
            dedupRepository.save(dedup);
            log.info("重复设备事件已忽略 key={} 第 {} 次重复", key, dedup.getDuplicateCount());
            return new ProcessResult("DUPLICATE_IGNORED", key, dedup.isLateArrival());
        }
        try {
            dedupRepository.save(DeviceEventDedup.builder()
                    .eventKey(key).sessionNo(event.getSessionNo())
                    .cabinetNo(event.getCabinetNo()).eventType(event.getType())
                    .firstArrivedAt(LocalDateTime.now()).duplicateCount(0).build());
        } catch (DataIntegrityViolationException race) {
            // 并发补传同一事件：另一事务已登记，直接忽略
            return new ProcessResult("DUPLICATE_IGNORED", key, false);
        }

        boolean late = dispatch(event, at, key);
        return new ProcessResult("PROCESSED", key, late);
    }

    /** 兼容旧调用 */
    @Transactional
    public void process(DeviceEvent event) {
        processDetailed(event);
    }

    private boolean dispatch(DeviceEvent event, LocalDateTime at, String key) {
        boolean lateArrival = false;
        switch (event.getType()) {
            case "DOOR_OPEN" -> sessionService.openDoor(
                    event.getCabinetNo(), event.getSessionNo(),
                    event.getUserCode(), event.getUserName(), at);
            case "RECOGNITION" -> {
                RecognitionEvent saved = sessionService.recordRecognition(
                        event.getSessionNo(), event.getCabinetNo(), event.getChannelCode(),
                        event.getSku(), event.getBatchNo(), event.getSource(), event.getAction(),
                        event.getQty(), event.getConfidence(), event.getWeightGram(),
                        event.getVideoClipUrl(), event.getClipStartSec(), event.getClipEndSec(),
                        at, null);
                DoorSession session = sessionRepository
                        .findBySessionNo(event.getSessionNo()).orElse(null);
                if (session != null && session.getStatus() != SessionStatus.OPEN) {
                    // 离线集中上报：关门/结算后补传的识别数据，只留存不重算
                    saved.setLateArrival(true);
                    eventRepository.save(saved);
                    lateArrival = true;
                    ReviewCase rc = reviewRepository.findBySessionId(session.getId()).orElse(null);
                    String note = "补传 " + event.getSource() + " " + event.getAction()
                            + " x" + event.getQty() + "（" + event.getChannelCode()
                            + "/" + event.getSku() + "）事件时间 " + at;
                    if (rc != null) {
                        reviewCaseService.noteLateData(rc.getReviewNo(), note);
                    } else {
                        alertService.raise(session.getCabinet(), AlertType.LATE_DEVICE_DATA, "INFO",
                                "会话 " + event.getSessionNo() + " 收到离线补传识别数据（不改变原结算）",
                                "SESSION", session.getId());
                    }
                    log.info("关门后补传识别事件已留存 session={} key={}", event.getSessionNo(), key);
                }
            }
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
        return lateArrival;
    }

    /** 幂等指纹：设备给了 eventId 用 eventId，否则按业务内容生成 */
    private String eventKey(DeviceEvent e, LocalDateTime at) {
        if (e.getEventId() != null && !e.getEventId().isBlank()) {
            return "ID:" + e.getEventId();
        }
        return switch (e.getType()) {
            case "DOOR_OPEN", "DOOR_CLOSE" ->
                    "SK:" + e.getType() + ":" + nullSafe(e.getSessionNo());
            case "RECOGNITION" -> "SK:RECOG:" + nullSafe(e.getSessionNo()) + ":"
                    + nullSafe(e.getChannelCode()) + ":" + nullSafe(e.getSku()) + ":"
                    + nullSafe(e.getSource()) + ":" + nullSafe(e.getAction()) + ":"
                    + Objects.toString(e.getQty(), "1") + ":" + at;
            case "TEMP" -> "SK:TEMP:" + nullSafe(e.getCabinetNo()) + ":" + e.getTempC() + ":" + at;
            case "HEARTBEAT" -> "SK:HB:" + nullSafe(e.getCabinetNo()) + ":" + at;
            case "CHANNEL_JAM" -> "SK:JAM:" + nullSafe(e.getCabinetNo()) + ":"
                    + nullSafe(e.getJamChannelCode()) + ":" + at;
            default -> "SK:OTHER:" + nullSafe(e.getCabinetNo()) + ":" + nullSafe(e.getSessionNo())
                    + ":" + e.getType() + ":" + at;
        };
    }

    private String nullSafe(String s) {
        return s == null ? "-" : s;
    }
}
