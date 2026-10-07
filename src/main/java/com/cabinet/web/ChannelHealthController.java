package com.cabinet.web;

import com.cabinet.domain.Channel;
import com.cabinet.service.ChannelHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Tag(name = "货道健康",
     description = "同一货道反复误扣款投诉监控：区分单次误识别与设备故障，必要时暂停货道")
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelHealthController {

    private final ChannelHealthService channelHealthService;

    @Operation(summary = "货道健康总览：窗口内误扣退款投诉数、状态、在途工单")
    @GetMapping("/health")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','CUSTOMER_SERVICE')")
    public List<ChannelHealthService.ChannelHealthView> health() {
        return channelHealthService.healthView();
    }

    @Operation(summary = "人工暂停货道（禁止上架）")
    @PostMapping("/{channelId}/suspend")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Channel suspend(@PathVariable Long channelId, @RequestBody Map<String, String> body,
                           Principal principal) {
        return channelHealthService.suspend(channelId, principal.getName(),
                body.getOrDefault("reason", "人工暂停"));
    }

    @Operation(summary = "恢复货道售卖")
    @PostMapping("/{channelId}/resume")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Channel resume(@PathVariable Long channelId, @RequestBody(required = false) Map<String, String> body,
                          Principal principal) {
        return channelHealthService.resume(channelId, principal.getName(),
                body == null ? "恢复售卖" : body.getOrDefault("note", "恢复售卖"));
    }
}
