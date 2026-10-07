package com.cabinet;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.dto.ChargeBasisView;
import com.cabinet.dto.ReviewEvidenceView;
import com.cabinet.kafka.DeviceEventProcessor;
import com.cabinet.kafka.dto.DeviceEvent;
import com.cabinet.repo.*;
import com.cabinet.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 误扣款复核流程集成测试（H2）：
 * 证据聚合研判、幂等退款（重复点击/重复申诉/设备补传）、
 * 识别样本修正、货道健康监控与维护换机、证据充分驳回与扣款依据。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestKafkaConfig.class)
class ReviewFlowIntegrationTest {

    @Autowired private TestFixtures fx;
    @Autowired private RestockService restockService;
    @Autowired private SessionSettlementService sessionService;
    @Autowired private ComplaintService complaintService;
    @Autowired private WalletService walletService;
    @Autowired private ChannelHealthService channelHealthService;
    @Autowired private MaintenanceService maintenanceService;
    @Autowired private ReviewAuditService auditService;
    @Autowired private DeviceEventProcessor deviceEventProcessor;
    @Autowired private RecognitionEventRepository eventRepository;
    @Autowired private ChannelStockRepository stockRepository;
    @Autowired private OrderRecordRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private DoorSessionRepository sessionRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private DeviceEventLogRepository eventLogRepository;
    @Autowired private MaintenanceOrderRepository maintenanceRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private WalletTxnRepository walletTxnRepository;

    private record Setup(AppUser replenisher, Product product, Cabinet cabinet,
                         Channel channel, ProductBatch batch) {}

    private Setup restock(int qty, String channelCode) {
        AppUser user = fx.user("REPLENISHER");
        Supplier supplier = fx.supplier();
        Product product = fx.chilledProduct(supplier);
        Cabinet cabinet = fx.chilledCabinet(fx.point(LocationType.COMMUNITY));
        Channel channel = fx.channel(cabinet, product, channelCode);
        ProductBatch batch = fx.batch(product, 7, 0);
        RestockOrder order = restockService.arrive(cabinet.getCabinetNo(),
                user.getUsername(), null, null);
        restockService.placeItem(order.getOrderNo(), product.getSku(), batch.getBatchNo(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(7),
                qty, channelCode, null, 4.0);
        restockService.complete(order.getOrderNo(), null);
        return new Setup(user, product, cabinet, channel, batch);
    }

    /** 典型误扣：拿起查看后放回，重量已恢复但视觉仍认为取走 */
    @Test
    void 重量恢复但视觉误判_复核退款后订单余额库存样本同步修正且幂等() {
        Setup d = restock(5, "A01");
        String sessionNo = "S-" + System.nanoTime();
        String userCode = "U900";
        sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, userCode, "张女士", null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "VISION", "PICK", 1, 0.95, null,
                "clip1.mp4", 2, 5, null, null, null, false);
        // 关门净重 0（商品已放回）：融合交叉校验矛盾 -> 挂起人工复核，不直接扣款
        OrderRecord pending = sessionService.closeDoor(sessionNo, 0, null);
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PENDING_REVIEW);
        assertThat(walletTxnRepository.findByUserCodeOrderByIdDesc(userCode)).isEmpty();

        // 人工误确认 -> 扣款（误扣款发生）
        OrderRecord charged = sessionService.confirmReview(sessionNo, null, "按视觉结果结算");
        assertThat(charged.getStatus()).isEqualTo(OrderStatus.CHARGED);
        assertThat(walletService.accountOf(userCode).getBalance())
                .isEqualByComparingTo("-5.50");

        // 用户投诉 -> 系统研判：重量已恢复但视觉认为取走，疑似误扣
        AppUser cs = fx.user("CUSTOMER_SERVICE");
        Complaint complaint = complaintService.submit(sessionNo, "误扣款", "拿起看了下就放回，仍被扣费");
        assertThat(complaint.getSystemVerdict())
                .isEqualTo(ReviewSuggestion.SUSPECT_MISCHARGE.name());

        // 证据聚合视图：开门/拿取/重量/视觉/货道盘点/扣款时间一屏可见
        ReviewEvidenceView ev = complaintService.reviewEvidence(complaint.getComplaintNo());
        assertThat(ev.verdict().suggestion()).isEqualTo(ReviewSuggestion.SUSPECT_MISCHARGE);
        assertThat(ev.verdict().weightRecovered()).isTrue();
        assertThat(ev.recognitionEvents()).hasSize(1);
        assertThat(ev.channelStocks()).hasSize(1);
        assertThat(ev.chargeTiming().totalAmount()).isEqualByComparingTo("5.50");
        assertThat(ev.chargeTiming().chargedAt()).isNotNull();
        assertThat(ev.audits()).anyMatch(a -> a.getAction().equals("AUTO_ANALYZE"));

        // 复核退款（商品已放回=已收回）-> 订单/余额/库存/样本同步修正
        Complaint resolved = complaintService.resolve(complaint.getComplaintNo(),
                cs.getUsername(), true, null, true, "重量已恢复，视觉误判，整单退款");
        assertThat(resolved.getStatus()).isEqualTo(ComplaintStatus.RESOLVED_REFUNDED);
        assertThat(resolved.getRefundAmount()).isEqualByComparingTo("5.50");
        // 订单状态
        assertThat(orderRepository.findById(charged.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
        assertThat(sessionRepository.findBySessionNo(sessionNo).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.SETTLED_REFUNDED);
        // 账户余额：扣 5.50 退 5.50 回到 0
        assertThat(walletService.accountOf(userCode).getBalance())
                .isEqualByComparingTo("0.00");
        assertThat(walletTxnRepository.findByUserCodeOrderByIdDesc(userCode))
                .hasSize(2)
                .anyMatch(t -> t.getTxnType() == WalletTxnType.CHARGE)
                .anyMatch(t -> t.getTxnType() == WalletTxnType.REFUND);
        // 货道库存回补
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(5);
        // 识别样本修正为误识别
        Long sessionId = sessionRepository.findBySessionNo(sessionNo).orElseThrow().getId();
        assertThat(eventRepository.findBySessionIdOrderByDetectedAtAsc(sessionId))
                .isNotEmpty()
                .allMatch(e -> e.getVerdict() == SampleVerdict.MISJUDGED
                        && e.getVerdictNote().contains(complaint.getComplaintNo()));
        // 审计留痕
        assertThat(auditService.trail("COMPLAINT", complaint.getComplaintNo()))
                .anyMatch(a -> a.getAction().equals("RESOLVE_REFUND"));

        // 重复点击：不二次退款
        Complaint again = complaintService.resolve(complaint.getComplaintNo(),
                cs.getUsername(), true, null, true, "重复点击");
        assertThat(again.getRefundAmount()).isEqualByComparingTo("5.50");
        assertThat(walletService.accountOf(userCode).getBalance())
                .isEqualByComparingTo("0.00");
        assertThat(walletTxnRepository.findByUserCodeOrderByIdDesc(userCode)).hasSize(2);
        assertThat(auditService.trail("COMPLAINT", complaint.getComplaintNo()))
                .anyMatch(a -> a.getAction().equals("RESOLVE_DUPLICATE_IGNORED"));
    }

    @Test
    void 设备重复投递与离线补传不造成二次扣款() {
        Setup d = restock(5, "A01");
        String sessionNo = "S-" + System.nanoTime();
        String userCode = "U901";

        DeviceEvent open = DeviceEvent.builder().type("DOOR_OPEN")
                .cabinetNo(d.cabinet.getCabinetNo()).sessionNo(sessionNo)
                .userCode(userCode).userName("离线用户").eventId("E-OPEN-1").build();
        deviceEventProcessor.process(open);
        deviceEventProcessor.process(open); // 设备重试重复投递
        assertThat(sessionRepository.findBySessionNo(sessionNo)).isPresent();
        // 服务层幂等：直接重复开门也不重复建会话
        sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, userCode, "离线用户", null);

        deviceEventProcessor.process(DeviceEvent.builder().type("RECOGNITION")
                .cabinetNo(d.cabinet.getCabinetNo()).sessionNo(sessionNo)
                .channelCode("A01").sku(d.product.getSku())
                .source("VISION").action("PICK").qty(1).confidence(0.96)
                .eventId("E-REC-1").build());
        deviceEventProcessor.process(DeviceEvent.builder().type("RECOGNITION")
                .cabinetNo(d.cabinet.getCabinetNo()).sessionNo(sessionNo)
                .channelCode("A01").sku(d.product.getSku())
                .source("RFID").action("PICK").qty(1).confidence(0.99)
                .eventId("E-REC-2").build());
        deviceEventProcessor.process(DeviceEvent.builder().type("DOOR_CLOSE")
                .cabinetNo(d.cabinet.getCabinetNo()).sessionNo(sessionNo)
                .weightDeltaGram(260).eventId("E-CLOSE-1").build());
        OrderRecord order = orderRepository.findBySessionId(
                sessionRepository.findBySessionNo(sessionNo).orElseThrow().getId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CHARGED);

        // 设备补传重复关门（同 eventId）-> 去重忽略
        deviceEventProcessor.process(DeviceEvent.builder().type("DOOR_CLOSE")
                .cabinetNo(d.cabinet.getCabinetNo()).sessionNo(sessionNo)
                .weightDeltaGram(260).eventId("E-CLOSE-1").backfill(true).build());
        assertThat(eventLogRepository.findByEventId("E-CLOSE-1").orElseThrow().getDupCount())
                .isEqualTo(1);

        // 设备离线恢复后补传识别事件（会话已结算）-> 仅存档，不触发二次结算
        deviceEventProcessor.process(DeviceEvent.builder().type("RECOGNITION")
                .cabinetNo(d.cabinet.getCabinetNo()).sessionNo(sessionNo)
                .channelCode("A01").sku(d.product.getSku())
                .source("VISION").action("PICK").qty(1).confidence(0.90)
                .eventId("E-REC-LATE").backfill(true).build());

        // 只有一笔扣款流水，订单未被改动
        assertThat(walletTxnRepository.findByUserCodeOrderByIdDesc(userCode))
                .hasSize(1)
                .allMatch(t -> t.getTxnType() == WalletTxnType.CHARGE);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CHARGED);
        // 补传事件已存档并打标
        Long sessionId = sessionRepository.findBySessionNo(sessionNo).orElseThrow().getId();
        assertThat(eventRepository.findBySessionIdOrderByDetectedAtAsc(sessionId))
                .anyMatch(e -> "E-REC-LATE".equals(e.getEventId()) && e.isBackfill());
        assertThat(auditService.trail("SESSION", sessionNo))
                .anyMatch(a -> a.getAction().equals("DEVICE_EVENT_BACKFILL"));
    }

    @Test
    void 重复申诉保留记录但不二次退款() {
        Setup d = restock(5, "A01");
        String sessionNo = "S-" + System.nanoTime();
        String userCode = "U902";
        sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, userCode, "李先生", null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "VISION", "PICK", 1, 0.99, null,
                null, null, null, null, null, null, false);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "RFID", "PICK", 1, 0.99, null,
                null, null, null, null, null, null, false);
        sessionService.closeDoor(sessionNo, 260, null);

        AppUser cs = fx.user("CUSTOMER_SERVICE");
        Complaint c1 = complaintService.submit(sessionNo, "误扣款", "第一次申诉");
        complaintService.resolve(c1.getComplaintNo(), cs.getUsername(),
                true, null, false, "首次退款");
        BigDecimal afterFirst = walletService.accountOf(userCode).getBalance();

        // 同一用户就同一会话再次申诉 -> 保留记录并关联首单
        Complaint c2 = complaintService.submit(sessionNo, "误扣款", "再次申诉要求退款");
        assertThat(c2.getDuplicateOfId()).isEqualTo(c1.getId());
        assertThat(auditService.trail("COMPLAINT", c2.getComplaintNo()))
                .anyMatch(a -> a.getAction().equals("DUPLICATE_APPEAL"));

        // 判责退款 -> 订单已退过，本单退款 0，余额不变
        Complaint resolved2 = complaintService.resolve(c2.getComplaintNo(),
                cs.getUsername(), true, null, false, "重复申诉");
        assertThat(resolved2.getRefundAmount()).isEqualByComparingTo("0");
        assertThat(walletService.accountOf(userCode).getBalance()).isEqualByComparingTo(afterFirst);
        assertThat(auditService.trail("COMPLAINT", c2.getComplaintNo()))
                .anyMatch(a -> a.getAction().equals("WALLET_TXN_SKIPPED"));
    }

    @Test
    void 同一货道反复误扣应暂停货道并派维护工单_换机留痕() {
        Setup d = restock(10, "A01");
        AppUser cs = fx.user("CUSTOMER_SERVICE");

        // 同一货道连续 3 次误扣退款
        for (int i = 0; i < 3; i++) {
            String sessionNo = "S-" + System.nanoTime();
            sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, "U90" + i, "用户" + i, null);
            sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                    d.product.getSku(), null, "VISION", "PICK", 1, 0.99, null,
                    null, null, null, null, null, null, false);
            sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                    d.product.getSku(), null, "RFID", "PICK", 1, 0.99, null,
                    null, null, null, null, null, null, false);
            sessionService.closeDoor(sessionNo, 260, null);
            Complaint c = complaintService.submit(sessionNo, "误扣款", "第" + (i + 1) + "次误扣");
            complaintService.resolve(c.getComplaintNo(), cs.getUsername(),
                    true, null, false, "误识别退款");
        }

        // 达到故障阈值：货道暂停 + 自动生成检查工单 + CRITICAL 告警
        Channel channel = channelRepository.findById(d.channel.getId()).orElseThrow();
        assertThat(channel.getStatus()).isEqualTo(ChannelStatus.SUSPENDED);
        List<MaintenanceOrder> orders = maintenanceRepository
                .findByChannelIdAndStatusIn(d.channel.getId(),
                        List.of(MaintenanceStatus.OPEN, MaintenanceStatus.IN_PROGRESS));
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).isAutoCreated()).isTrue();
        assertThat(orders.get(0).getType()).isEqualTo(MaintenanceType.INSPECT);
        assertThat(alertRepository.findAll())
                .anyMatch(a -> a.getAlertType() == AlertType.CHANNEL_HEALTH
                        && "CRITICAL".equals(a.getSeverity()));

        // 暂停货道禁止上架
        RestockOrder ro = restockService.arrive(d.cabinet.getCabinetNo(),
                d.replenisher.getUsername(), null, null);
        assertThatThrownBy(() -> restockService.placeItem(ro.getOrderNo(),
                d.product.getSku(), d.batch.getBatchNo(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(7),
                2, "A01", null, 4.0))
                .hasMessageContaining("已暂停");

        // 检查工单完成 -> 货道恢复
        maintenanceService.complete(orders.get(0).getMoNo(), "op", "现场检查传感器偏移已校准", null);
        assertThat(channelRepository.findById(d.channel.getId()).orElseThrow().getStatus())
                .isEqualTo(ChannelStatus.NORMAL);

        // 换机：新旧设备序列号留痕，货道历史记录保留
        MaintenanceOrder replace = maintenanceService.createManual(
                d.cabinet.getCabinetNo(), "A01", MaintenanceType.REPLACE, "传感器老化换机", "op");
        maintenanceService.start(replace.getMoNo(), "op");
        MaintenanceOrder done = maintenanceService.complete(replace.getMoNo(), "op",
                "整模块更换", "EQ-NEW-2026");
        assertThat(done.getNewEquipmentSn()).isEqualTo("EQ-NEW-2026");
        assertThat(channelRepository.findById(d.channel.getId()).orElseThrow().getEquipmentSn())
                .isEqualTo("EQ-NEW-2026");
        // 健康视图与审计轨迹仍可追溯历史
        assertThat(channelHealthService.healthView())
                .anyMatch(h -> h.channelCode().equals("A01")
                        && h.cabinetNo().equals(d.cabinet.getCabinetNo())
                        && h.refundedComplaintsInWindow() >= 3);
        assertThat(auditService.trail("CHANNEL", d.cabinet.getCabinetNo() + "/A01"))
                .anyMatch(a -> a.getAction().equals("CHANNEL_SUSPENDED"))
                .anyMatch(a -> a.getAction().equals("MAINTENANCE_COMPLETED"));
    }

    @Test
    void 证据充分应驳回并向用户展示扣款依据() {
        Setup d = restock(5, "A01");
        String sessionNo = "S-" + System.nanoTime();
        String userCode = "U905";
        sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, userCode, "赵先生", null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "VISION", "PICK", 1, 0.98, null,
                "take.mp4", 3, 6, null, null, null, false);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "RFID", "PICK", 1, 0.99, null,
                null, null, null, null, null, null, false);
        OrderRecord order = sessionService.closeDoor(sessionNo, 260, null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CHARGED);

        AppUser cs = fx.user("CUSTOMER_SERVICE");
        Complaint complaint = complaintService.submit(sessionNo, "误扣款", "我没拿东西");
        // 多源一致 + 重量减少 + 库存减少 -> 证据充分
        assertThat(complaint.getSystemVerdict())
                .isEqualTo(ReviewSuggestion.EVIDENCE_SUFFICIENT.name());

        Complaint rejected = complaintService.resolve(complaint.getComplaintNo(),
                cs.getUsername(), false, null, false, "多源证据一致，驳回");
        assertThat(rejected.getStatus()).isEqualTo(ComplaintStatus.RESOLVED_REJECTED);
        // 订单保持扣款，余额不变，样本标记为确认正确
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CHARGED);
        assertThat(walletService.accountOf(userCode).getBalance())
                .isEqualByComparingTo("-5.50");
        Long sessionId = sessionRepository.findBySessionNo(sessionNo).orElseThrow().getId();
        assertThat(eventRepository.findBySessionIdOrderByDetectedAtAsc(sessionId))
                .allMatch(e -> e.getVerdict() == SampleVerdict.CONFIRMED);

        // 用户侧扣款依据
        ChargeBasisView basis = complaintService.chargeBasis(order.getOrderNo());
        assertThat(basis.items()).hasSize(1);
        assertThat(basis.totalAmount()).isEqualByComparingTo("5.50");
        assertThat(basis.evidenceSummary()).isNotEmpty();
        assertThat(basis.videoClips()).hasSize(1);
        assertThat(basis.videoClips().get(0).url()).isEqualTo("take.mp4");
    }

    @Test
    void 钱包扣款退款按业务单号幂等() {
        walletService.charge("U906", new BigDecimal("10.00"), "ORDER", 999001L, "扣款");
        WalletTxn dup = walletService.charge("U906", new BigDecimal("10.00"), "ORDER", 999001L, "扣款");
        assertThat(walletService.accountOf("U906").getBalance())
                .isEqualByComparingTo("-10.00");
        assertThat(walletTxnRepository.findByUserCodeOrderByIdDesc("U906")).hasSize(1);
        assertThat(dup.getAmount()).isEqualByComparingTo("10.00");

        walletService.refund("U906", new BigDecimal("10.00"), "COMPLAINT", 888001L, "退款");
        walletService.refund("U906", new BigDecimal("10.00"), "COMPLAINT", 888001L, "退款");
        assertThat(walletService.accountOf("U906").getBalance()).isEqualByComparingTo("0.00");
        assertThat(walletTxnRepository.findByUserCodeOrderByIdDesc("U906")).hasSize(2);
    }
}
