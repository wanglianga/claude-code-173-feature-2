package com.cabinet.web;

import com.cabinet.config.KafkaConfig;
import com.cabinet.kafka.DeviceEventProcessor;
import com.cabinet.kafka.dto.DeviceEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 设备模拟入口：真实柜机通过该报文结构向 cabinet.device.event 主题上报。
 * 默认投递 Kafka（异步消费）；sync=true 时直接同步处理（便于无消息积压地联调）。
 */
@Tag(name = "设备事件", description = "开门/关门/识别/温度/心跳/卡货事件上报（投递 Kafka）")
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final KafkaTemplate<String, DeviceEvent> kafkaTemplate;
    private final DeviceEventProcessor processor;

    @Operation(summary = "上报单个设备事件（默认进 Kafka；sync=true 同步处理）。"
            + "重复 eventId/内容指纹自动忽略；关门后补传识别只留存不重算")
    @PostMapping("/events")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public Map<String, Object> publish(@RequestBody DeviceEvent event,
                                       @RequestParam(defaultValue = "false") boolean sync)
            throws Exception {
        if (sync) {
            DeviceEventProcessor.ProcessResult r = processor.processDetailed(event);
            return Map.of("mode", "sync", "result", r.status(),
                    "eventKey", r.eventKey(), "lateArrival", r.lateArrival());
        }
        String key = event.getCabinetNo() != null ? event.getCabinetNo() : event.getSessionNo();
        kafkaTemplate.send(KafkaConfig.TOPIC_DEVICE, key, event).get();
        return Map.of("mode", "kafka", "topic", KafkaConfig.TOPIC_DEVICE, "key", key);
    }

    @Operation(summary = "批量上报设备事件（按顺序投递同一柜机分区；逐条幂等去重）")
    @PostMapping("/events/batch")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public Map<String, Object> publishBatch(@RequestBody List<DeviceEvent> events,
                                            @RequestParam(defaultValue = "false") boolean sync)
            throws Exception {
        List<String> keys = new ArrayList<>();
        int processed = 0, duplicated = 0, late = 0;
        for (DeviceEvent e : events) {
            if (sync) {
                DeviceEventProcessor.ProcessResult r = processor.processDetailed(e);
                if ("DUPLICATE_IGNORED".equals(r.status())) duplicated++; else processed++;
                if (r.lateArrival()) late++;
                keys.add(r.eventKey());
            } else {
                String key = e.getCabinetNo() != null ? e.getCabinetNo() : e.getSessionNo();
                kafkaTemplate.send(KafkaConfig.TOPIC_DEVICE, key, e).get();
                keys.add(key);
            }
        }
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("mode", sync ? "sync" : "kafka");
        resp.put("count", events.size());
        resp.put("processed", processed);
        resp.put("duplicateIgnored", duplicated);
        resp.put("lateArrival", late);
        resp.put("keys", keys);
        return resp;
    }
}
