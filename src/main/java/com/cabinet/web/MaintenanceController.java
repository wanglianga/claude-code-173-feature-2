package com.cabinet.web;

import com.cabinet.domain.MaintenanceOrder;
import com.cabinet.domain.enums.MaintenanceStatus;
import com.cabinet.domain.enums.MaintenanceType;
import com.cabinet.service.MaintenanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@Tag(name = "维护工单",
     description = "货道检查/维修/换机工单；换机记录新旧设备序列号，历史记录保留")
@RestController
@RequestMapping("/api/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    @Operation(summary = "工单列表（status=OPEN/IN_PROGRESS/DONE 过滤）")
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public List<MaintenanceOrder> list(@RequestParam(required = false) MaintenanceStatus status) {
        return maintenanceService.list(status);
    }

    public record CreateRequest(String cabinetNo, String channelCode,
                                MaintenanceType type, String reason) {}

    @Operation(summary = "人工创建维护工单")
    @PostMapping
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public MaintenanceOrder create(@RequestBody CreateRequest req, Principal principal) {
        return maintenanceService.createManual(req.cabinetNo(), req.channelCode(),
                req.type() == null ? MaintenanceType.INSPECT : req.type(),
                req.reason(), principal.getName());
    }

    @Operation(summary = "开始处理工单")
    @PostMapping("/{moNo}/start")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public MaintenanceOrder start(@PathVariable String moNo, Principal principal) {
        return maintenanceService.start(moNo, principal.getName());
    }

    public record CompleteRequest(String resolution, String newEquipmentSn) {}

    @Operation(summary = "完成工单（REPLACE 换机须填新设备序列号；完成后暂停货道自动恢复）")
    @PostMapping("/{moNo}/complete")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public MaintenanceOrder complete(@PathVariable String moNo, @RequestBody CompleteRequest req,
                                     Principal principal) {
        return maintenanceService.complete(moNo, principal.getName(),
                req.resolution(), req.newEquipmentSn());
    }
}
