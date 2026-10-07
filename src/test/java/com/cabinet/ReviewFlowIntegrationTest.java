package com.cabinet;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
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
 * 重量恢复但视觉判取走 -> 转人工 -> 退款幂等回补（订单/余额/库存/样本同步修正）；
 * 证据充分维持扣款；重复申诉；设备重复/补传事件去重；
 * 同货道反复误判自动暂停维护；已结算订单跨期退款修正；货道换机保留原始证据。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestKafkaConfig.class)
class ReviewFlowIntegrationTest {

    @Autowired private TestFixtures fx;
    @Autowired private RestockService restockService;
    @Autowired private SessionSettlementService sessionService;
    @Autowired private ComplaintService complaintService;
    @Autowired private ReviewCaseService reviewService;
    @Autowired private AccountService accountService;
    @Autowired private SettlementService settlementService;
    @Autowired private StocktakeService stocktakeService;
    @Autowired private ChannelMaintenanceService maintenanceService;
    @Autowired private DeviceEventProcessor processor;

    @Autowired private ChannelStockRepository stockRepository;
    @Autowired private OrderRecordRepository orderRepository;
    @Autowired private RecognitionEventRepository eventRepository;
    @Autowired private AccountTxnRepository txnRepository;
    @Autowired private RefundRecordRepository refundRepository;
    @Autowired private SampleCorrectionRepository sampleRepository;
    @Autowired private ChannelHealthRecordRepository healthRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private ReviewCaseRepository reviewRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private CabinetRepository cabinetRepository;
    @Autowired private StocktakeOrderRepository stocktakeOrderRepository;
    @Autowired private ProductRepository productRepository;

    private record Env(AppUser user, Supplier supplier, Product product, Cabinet cabinet,
                       Channel channel, ProductBatch batch, String channelCode) {}

    /** 无单件重量的商品：关门无法做称重交叉校验，重量已恢复时仍可能仅凭视觉/RFID 扣款 */
    private Env setup(boolean withUnitWeight) {
        AppUser user = fx.user("REPLENISHER");
        Supplier supplier = fx.supplier();
        Product product = productRepositorySave(supplier, withUnitWeight);
        Point point = fx.point(LocationType.COMMUNITY);
        Cabinet cabinet = fx.chilledCabinet(point);
        Channel channel = fx.channel(cabinet, product, "A01");
        ProductBatch batch = fx.batch(product, 7, 0);
        RestockOrder order = restockService.arrive(cabinet.getCabinetNo(),
                user.getUsername(), "photo.jpg", null);
        restockService.placeItem(order.getOrderNo(), product.getSku(), batch.getBatchNo(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(7),
                8, "A01", "photo2.jpg", 4.0);
        restockService.complete(order.getOrderNo(), null);
        return new Env(user, supplier, product, cabinet, channel, batch, "A01");
    }

    private Product productRepositorySave(Supplier supplier, boolean withWeight) {
        var b = Product.builder().sku("SKU-" + System.nanoTime()).name("测试茶饮").category("饮品")
                .tempZone(TempZone.CHILLED).shelfLifeDays(7).supplier(supplier)
                .costPrice(new BigDecimal("3.00")).retailPrice(new BigDecimal("6.00"));
        if (withWeight) b.unitWeightGram(260);
        return productRepository.save(b.build());
    }

    /** 视觉+RFID 识别拿起 qty 件并关门扣款 */
    private String chargedSession(Env env, int qty, Integer weightDelta, String userCode) {
        String sn = "S-" + System.nanoTime();
        sessionService.openDoor(env.cabinet.getCabinetNo(), sn, userCode, "复核测试用户", null);
        sessionService.recordRecognition(sn, env.cabinet.getCabinetNo(), "A01",
                env.product.getSku(), null, "VISION", "PICK", qty, 0.99,
                null, "clip.mp4", 0, 3, null, null);
        sessionService.recordRecognition(sn, env.cabinet.getCabinetNo(), "A01",
                env.product.getSku(), null, "RFID", "PICK", qty, 0.99,
                null, null, null, null, null, null);
        OrderRecord order = sessionService.closeDoor(sn, weightDelta, null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CHARGED);
        return sn;
    }

    @Test
    void 重量恢复但视觉判取走应转人工退款并同步修正() {
        Env env = setup(false);
        String userCode = "U-CASE-" + System.nanoTime();
        accountService.recharge(userCode, "复核测试用户", new BigDecimal("50.00"), "测试充值");
        String sn = chargedSession(env, 1, 0, userCode); // 关门净重变化 0：重量已恢复

        Complaint cp = complaintService.submit(sn, "误扣款", "拿起看了看放回去了");
        AppUser cs = fx.user("CUSTOMER_SERVICE");
        ReviewCase rc = reviewService.openFromComplaint(cp.getComplaintNo(), "A01", cs.getUsername());

        assertThat(rc.getStatus()).isEqualTo(ReviewStatus.MANUAL_REVIEW);
        assertThat(rc.getInitialVerdict()).isEqualTo(ReviewVerdict.REFUND_SUGGESTED);

        ReviewEvidenceView ev = reviewService.evidence(rc.getReviewNo());
        assertThat(ev.weightRestored()).isTrue();
        assertThat(ev.sourceNets()).anyMatch(s -> s.source().equals("VISION") && s.net() == 1);
        assertThat(ev.conflictReasons()).anyMatch(r -> r.contains("重量已恢复"));

        // 退款前余额：50 - 6 = 44
        assertThat(accountService.get(userCode).getBalance()).isEqualByComparingTo("44.00");

        ReviewCase done = reviewService.grantRefund(rc.getReviewNo(), cs.getUsername(),
                null, false, "视频与重量均证明已放回，整单退");
        assertThat(done.getStatus()).isEqualTo(ReviewStatus.REFUNDED);
        assertThat(done.getRefundAmount()).isEqualByComparingTo("6.00");

        // 订单状态、账户余额同步修正
        OrderRecord order = orderRepository.findBySessionId(rc.getSession().getId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(accountService.get(userCode).getBalance()).isEqualByComparingTo("50.00");
        // 商品未收回：库存不回补（仍为 7），登记报损
        assertThat(stockRepository.findByChannelId(env.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(7);
        // 只有一条退款流水
        List<AccountTxn> refunds = txnRepository.findByUserCodeOrderByIdDesc(userCode).stream()
                .filter(t -> t.getType() == AccountTxnType.REFUND).toList();
        assertThat(refunds).hasSize(1);
        // 识别样本标记为误识别
        assertThat(sampleRepository.findByReviewCaseId(rc.getId()))
                .anyMatch(s -> s.getStatus() == SampleStatus.INVALIDATED);
        // 货道首次误判：观察态，不暂停
        ChannelHealthRecord h = healthRepository.findByChannelId(env.channel.getId()).orElseThrow();
        assertThat(h.getHealth()).isEqualTo(ChannelHealth.SUSPECT);
        assertThat(h.getMiscountInWindow()).isEqualTo(1);
    }

    @Test
    void 重复点击退款不会二次退款() {
        Env env = setup(false);
        String userCode = "U-CASE-" + System.nanoTime();
        accountService.recharge(userCode, "用户", new BigDecimal("20.00"), "充值");
        String sn = chargedSession(env, 1, 0, userCode);
        Complaint cp = complaintService.submit(sn, "误扣款", "放回了");
        AppUser cs = fx.user("CUSTOMER_SERVICE");
        ReviewCase rc = reviewService.openFromComplaint(cp.getComplaintNo(), "A01", cs.getUsername());

        reviewService.grantRefund(rc.getReviewNo(), cs.getUsername(), null, false, "误判退款");
        // 客服重复点击 / 设备补传触发重试
        ReviewCase again = reviewService.grantRefund(rc.getReviewNo(), cs.getUsername(),
                null, false, "重复提交");
        assertThat(again.getStatus()).isEqualTo(ReviewStatus.REFUNDED);
        assertThat(again.getRefundAmount()).isEqualByComparingTo("6.00");

        assertThat(refundRepository.findByOrderIdOrderByIdAsc(rc.getOrder().getId())).hasSize(1);
        long refundTxns = txnRepository.findByUserCodeOrderByIdDesc(userCode).stream()
                .filter(t -> t.getType() == AccountTxnType.REFUND).count();
        assertThat(refundTxns).isEqualTo(1);
        assertThat(accountService.get(userCode).getBalance()).isEqualByComparingTo("20.00");

        // 投诉直退也不能对已退款单再退
        assertThatThrownBy(() -> complaintService.resolve(cp.getComplaintNo(),
                cs.getUsername(), true, null, false, "再退一次"))
                .hasMessageContaining("已结案");
    }

    @Test
    void 证据充分应维持扣款并展示依据样本标记有效() {
        Env env = setup(true);
        String sn = chargedSession(env, 1, 260, "U-CASE-"+System.nanoTime()); // 称重匹配
        Complaint cp = complaintService.submit(sn, "误扣款", "我觉得没拿");
        AppUser cs = fx.user("CUSTOMER_SERVICE");
        ReviewCase rc = reviewService.openFromComplaint(cp.getComplaintNo(), "A01", cs.getUsername());

        assertThat(rc.getStatus()).isEqualTo(ReviewStatus.AUTO_PENDING);
        assertThat(rc.getInitialVerdict()).isEqualTo(ReviewVerdict.CONFIRM_SUGGESTED);

        ReviewEvidenceView ev = reviewService.evidence(rc.getReviewNo());
        assertThat(ev.weightRestored()).isFalse();
        assertThat(ev.doorToChargeMillis()).isNotNull();

        reviewService.uphold(rc.getReviewNo(), cs.getUsername(), "多源一致称重匹配，扣款成立");
        assertThat(reviewRepository.findByReviewNo(rc.getReviewNo()).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.UPHELD);
        assertThat(sampleRepository.findByReviewCaseId(rc.getId()))
                .allMatch(s -> s.getStatus() == SampleStatus.REAFFIRMED);
        assertThat(cp.getComplaintNo()) // 投诉单状态联动
                .isNotNull();
        // 时间线含系统初判与维持操作
        assertThat(reviewService.timeline(rc.getReviewNo()))
                .anyMatch(l -> l.getAction().equals("SYSTEM_INITIAL"))
                .anyMatch(l -> l.getAction().equals("UPHOLD"));
    }

    @Test
    void 重复申诉应关联首单不重复退款() {
        Env env = setup(false);
        String sn = chargedSession(env, 1, 0, "U-CASE-"+System.nanoTime());
        Complaint first = complaintService.submit(sn, "误扣款", "第一次");
        Complaint second = complaintService.submit(sn, "误扣款", "第二次催");
        assertThat(second.isDuplicate()).isTrue();
        assertThat(second.getStatus()).isEqualTo(ComplaintStatus.DUPLICATE);
        assertThat(second.getDuplicateOfId()).isEqualTo(first.getId());
        assertThatThrownBy(() -> reviewService.openFromComplaint(
                second.getComplaintNo(), "A01", "service1"))
                .hasMessageContaining("重复申诉");
    }

    @Test
    void 设备重复事件去重且关门后补传只留存不改结算() {
        Env env = setup(true);
        // 通过设备事件入口（含幂等去重）
        String sn = "S-DEV-" + System.nanoTime();
        processor.processDetailed(DeviceEvent.builder().type("DOOR_OPEN")
                .cabinetNo(env.cabinet.getCabinetNo()).sessionNo(sn)
                .userCode("UDEV").userName("设备用户").build());
        DeviceEvent pick = DeviceEvent.builder().type("RECOGNITION")
                .cabinetNo(env.cabinet.getCabinetNo()).sessionNo(sn).channelCode("A01")
                .sku(env.product.getSku()).source("VISION").action("PICK").qty(1)
                .confidence(0.99).eventId("EVT-PICK-1").build();
        assertThat(processor.processDetailed(pick).status()).isEqualTo("PROCESSED");
        // 网络重发同一事件
        assertThat(processor.processDetailed(pick).status()).isEqualTo("DUPLICATE_IGNORED");

        processor.processDetailed(DeviceEvent.builder().type("RECOGNITION")
                .cabinetNo(env.cabinet.getCabinetNo()).sessionNo(sn).channelCode("A01")
                .sku(env.product.getSku()).source("RFID").action("PICK").qty(1)
                .confidence(0.99).eventId("EVT-RFID-1").build());
        DeviceEvent close = DeviceEvent.builder().type("DOOR_CLOSE")
                .cabinetNo(env.cabinet.getCabinetNo()).sessionNo(sn)
                .weightDeltaGram(260).eventId("EVT-CLOSE-1").build();
        processor.processDetailed(close);
        // 关门事件重复不能二次结算
        assertThat(processor.processDetailed(close).status()).isEqualTo("DUPLICATE_IGNORED");

        DoorSession ds = lookupSession(sn);
        assertThat(ds.getStatus()).isEqualTo(SessionStatus.SETTLED);
        long visionEvents = eventRepository.findBySessionIdOrderByDetectedAtAsc(ds.getId())
                .stream().filter(e -> e.getSource() == RecognitionSource.VISION).count();
        assertThat(visionEvents).isEqualTo(1);

        // 离线恢复后集中补传一条“放回”识别：只留存并标记，不改原结算
        var late = processor.processDetailed(DeviceEvent.builder().type("RECOGNITION")
                .cabinetNo(env.cabinet.getCabinetNo()).sessionNo(sn).channelCode("A01")
                .sku(env.product.getSku()).source("VISION").action("PUT").qty(1)
                .confidence(0.9).eventId("EVT-LATE-PUT").build());
        assertThat(late.status()).isEqualTo("PROCESSED");
        assertThat(late.lateArrival()).isTrue();
        assertThat(eventRepository.findBySessionIdAndLateArrivalTrue(ds.getId())).hasSize(1);
    }

    @Test
    void 同货道反复误判达到阈值应暂停货道并拦截自动结算() {
        Env env = setup(false);
        AppUser op = fx.user("OPERATOR");
        for (int i = 0; i < 3; i++) {
            String sn = chargedSession(env, 1, 0, "U-CASE-"+System.nanoTime());
            Complaint cp = complaintService.submit(sn, "误扣款", "第 " + (i + 1) + " 次");
            ReviewCase rc = reviewService.openFromComplaint(cp.getComplaintNo(), "A01", op.getUsername());
            reviewService.grantRefund(rc.getReviewNo(), op.getUsername(), null, false, "反复误判");
        }
        Channel channel = channelRepository.findById(env.channel.getId()).orElseThrow();
        ChannelHealthRecord h = healthRepository.findByChannelId(channel.getId()).orElseThrow();
        assertThat(h.getHealth()).isEqualTo(ChannelHealth.SUSPENDED);
        assertThat(channel.isSuspended()).isTrue();
        assertThat(alertRepository.findByCabinetIdOrderByIdDesc(env.cabinet.getId()))
                .anyMatch(a -> a.getAlertType() == AlertType.CHANNEL_SUSPENDED);

        // 暂停后新购物关门不能自动扣款，挂起复核
        String sn = "S-SUSP-" + System.nanoTime();
        sessionService.openDoor(env.cabinet.getCabinetNo(), sn, "UX", "暂停货道用户", null);
        sessionService.recordRecognition(sn, env.cabinet.getCabinetNo(), "A01",
                env.product.getSku(), null, "VISION", "PICK", 1, 0.99, null,
                null, null, null, null, null);
        sessionService.recordRecognition(sn, env.cabinet.getCabinetNo(), "A01",
                env.product.getSku(), null, "RFID", "PICK", 1, 0.99, null,
                null, null, null, null, null);
        OrderRecord pending = sessionService.closeDoor(sn, 0, null);
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PENDING_REVIEW);
        assertThat(pending.getNote()).contains("暂停");

        // 维护完成恢复
        maintenanceService.resume(env.cabinet.getCabinetNo(), "A01", fx.user("OPERATOR").getUsername(), "更换视觉模组");
        assertThat(channelRepository.findById(env.channel.getId()).orElseThrow().isSuspended())
                .isFalse();
        assertThat(healthRepository.findByChannelId(env.channel.getId()).orElseThrow().getHealth())
                .isEqualTo(ChannelHealth.MAINTAINED);
    }

    @Test
    void 已结算订单退款应记录跨期修正且结算口径不被改写() {
        Env env = setup(true);
        String sn = chargedSession(env, 1, 260, "U-CASE-"+System.nanoTime());
        LocalDate today = LocalDate.now();
        SettlementBatch stl = settlementService.generate(env.supplier.getId(),
                today.minusDays(1), today.plusDays(1));
        SettlementBatch confirmed = settlementService.confirm(stl.getSettlementNo());
        assertThat(confirmed.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);

        Complaint cp = complaintService.submit(sn, "误扣款", "结算后才发现");
        AppUser op = fx.user("OPERATOR");
        ReviewCase rc = reviewService.openFromComplaint(cp.getComplaintNo(), "A01", op.getUsername());
        ReviewCase done = reviewService.grantRefund(rc.getReviewNo(), op.getUsername(),
                null, true, "结算后复核确认误判");
        assertThat(done.getStatus()).isEqualTo(ReviewStatus.REFUNDED);
        RefundRecord rf = refundRepository.findByOrderIdOrderByIdAsc(rc.getOrder().getId())
                .get(0);
        assertThat(rf.getSettlementImpact()).contains("跨期修正");
        // 商品收回：库存回补到 8
        assertThat(stockRepository.findByChannelId(env.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(8);
        // 原结算单状态保持已确认（修正只追加台账，不回写历史结算）
        assertThat(confirmed.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
    }

    @Test
    void 盘点盘盈反证商品仍在货道应建议退款() {
        Env env = setup(true);
        String sn = chargedSession(env, 1, 260, "U-CASE-"+System.nanoTime()); // 设备误报重量减少，已扣款
        // 盘点显示实物比系统多 1（商品其实还在）
        AppUser op = fx.user("OPERATOR");
        StocktakeOrder st = stocktakeService.create(env.cabinet.getCabinetNo(), op.getUsername());
        stocktakeService.count(st.getOrderNo(), List.of(
                new StocktakeService.CountLine("A01", env.batch.getBatchNo(), 8))); // 系统 7，实盘 8

        Complaint cp = complaintService.submit(sn, "误扣款", "我放回去了");
        ReviewCase rc = reviewService.openFromComplaint(cp.getComplaintNo(), "A01", op.getUsername());
        assertThat(rc.getInitialVerdict()).isEqualTo(ReviewVerdict.REFUND_SUGGESTED);
        ReviewEvidenceView ev = reviewService.evidence(rc.getReviewNo());
        assertThat(ev.stocktakeLines()).isNotEmpty();
        reviewService.grantRefund(rc.getReviewNo(), op.getUsername(), null, true, "盘盈反证，退款收回");
        assertThat(stocktakeOrderRepository.findByOrderNo(st.getOrderNo()).orElseThrow()
                .getStatus()).isEqualTo(StocktakeStatus.PENDING_ADJUST);
    }

    @Test
    void 货道换机应保留原机证据并在新机记录迁移来源() {
        Env env = setup(false);
        String sn = chargedSession(env, 1, 0, "U-CASE-"+System.nanoTime());
        Complaint cp = complaintService.submit(sn, "误扣款", "换机前投诉");
        ReviewCase rc = reviewService.openFromComplaint(cp.getComplaintNo(), "A01", fx.user("OPERATOR").getUsername());

        Cabinet newCab = fx.chilledCabinet(fx.point(LocationType.OFFICE));
        maintenanceService.replaceCabinet(env.cabinet.getCabinetNo(),
                newCab.getCabinetNo(), fx.user("OPERATOR").getUsername(), "视觉模组故障整体换机");

        assertThat(cabinetRepository.findByCabinetNo(env.cabinet.getCabinetNo()).orElseThrow()
                .getStatus()).isEqualTo(CabinetStatus.OFFLINE);
        Channel newChannel = channelRepository
                .findByCabinetIdAndCode(newCab.getId(), "A01").orElseThrow();
        assertThat(newChannel.getMigratedFromCabinetNo()).isEqualTo(env.cabinet.getCabinetNo());

        // 原始证据仍归属旧柜机，复核可继续
        ReviewEvidenceView ev = reviewService.evidence(rc.getReviewNo());
        assertThat(ev.cabinetNo()).isEqualTo(env.cabinet.getCabinetNo());
        reviewService.noteCabinetReplaced(rc.getReviewNo(), newCab.getCabinetNo(), fx.user("OPERATOR").getUsername());
        assertThat(reviewService.timeline(rc.getReviewNo()))
                .anyMatch(l -> l.getAction().equals("CABINET_REPLACED"));
    }

    @Autowired private DoorSessionRepository doorSessionRepository;
    private DoorSession lookupSession(String sn) {
        return doorSessionRepository.findBySessionNo(sn).orElseThrow();
    }
}
