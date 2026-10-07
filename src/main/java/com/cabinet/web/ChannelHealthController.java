package com.cabinet.web;

import com.cabinet.domain.Channel;
import com.cabinet.domain.ChannelHealthRecord;
import com.cabinet.domain.ChannelMaintenanceLog;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.CabinetRepository;
import com.cabinet.repo.ChannelHealthRecordRepository;
import com.cabinet.repo.ChannelRepository;
import com.cabinet.service.ChannelHealthService;
import com.cabinet.service.ChannelMaintenanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 货道健康度与维护：查看反复误识别的货道（区分单次误识别与设备故障），
 * 人工暂停/维护恢复/设备换机。
 */
@Tag(name = "货道健康与维护", description = "货道误判统计、暂停售卖、维护恢复、设备换机")
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelHealthController {

    private final ChannelRepository channelRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelHealthRecordRepository healthRepository;
    private final ChannelHealthService healthService;
    private final ChannelMaintenanceService maintenanceService;

    public record RemarkRequest(String remark) {}
    public record ReplaceRequest(String oldCabinetNo, String newCabinetNo, String remark) {}

    @Operation(summary = "查询货道健康档案（窗口/累计误判次数、健康状态、暂停原因）")
    @GetMapping("/health")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','CUSTOMER_SERVICE','FINANCE')")
    public List<ChannelHealthRecord> listHealth() {
        return healthRepository.findAll();
    }

    @Operation(summary = "查询指定货道健康档案")
    @GetMapping("/{cabinetNo}/{channelCode}/health")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','CUSTOMER_SERVICE','FINANCE')")
    public ChannelHealthRecord health(@PathVariable String cabinetNo,
                                      @PathVariable String channelCode) {
        Channel ch = mustChannel(cabinetNo, channelCode);
        ChannelHealthRecord h = healthService.get(ch);
        if (h == null) {
            throw ApiException.notFound("货道暂无误判记录（HEALTHY）");
        }
        return h;
    }

    @Operation(summary = "人工暂停货道售卖（安排维护）")
    @PostMapping("/{cabinetNo}/{channelCode}/suspend")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public ChannelMaintenanceLog suspend(@PathVariable String cabinetNo,
                                         @PathVariable String channelCode,
                                         @RequestBody RemarkRequest req, Principal principal) {
        return maintenanceService.suspend(cabinetNo, channelCode, principal.getName(),
                req.remark());
    }

    @Operation(summary = "维护完成，恢复货道售卖并重置误判窗口")
    @PostMapping("/{cabinetNo}/{channelCode}/resume")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public ChannelMaintenanceLog resume(@PathVariable String cabinetNo,
                                        @PathVariable String channelCode,
                                        @RequestBody RemarkRequest req, Principal principal) {
        return maintenanceService.resume(cabinetNo, channelCode, principal.getName(),
                req.remark());
    }

    @Operation(summary = "货道维护/换机留痕历史")
    @GetMapping("/{cabinetNo}/{channelCode}/maintenance")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','CUSTOMER_SERVICE','FINANCE')")
    public List<ChannelMaintenanceLog> maintenance(@PathVariable String cabinetNo,
                                                   @PathVariable String channelCode) {
        return maintenanceService.history(cabinetNo, channelCode);
    }

    @Operation(summary = "设备换机：旧柜机下线保留历史证据，新机同编码货道记录迁移来源")
    @PostMapping("/replace-cabinet")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Map<String, Object> replace(@RequestBody ReplaceRequest req, Principal principal) {
        List<ChannelMaintenanceLog> logs = maintenanceService.replaceCabinet(
                req.oldCabinetNo(), req.newCabinetNo(), principal.getName(), req.remark());
        return Map.of("replacedChannels", logs.size(),
                "oldCabinetNo", req.oldCabinetNo(),
                "newCabinetNo", req.newCabinetNo());
    }

    private Channel mustChannel(String cabinetNo, String code) {
        var cab = cabinetRepository.findByCabinetNo(cabinetNo)
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        return channelRepository.findByCabinetIdAndCode(cab.getId(), code)
                .orElseThrow(() -> ApiException.notFound("货道不存在: " + code));
    }
}
