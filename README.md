# 无人货柜补货盘点临期下架服务

面向城市社区、办公楼、医院无人货柜场景的后端服务，覆盖补货员补货上架、柜机多源识别下单、临期/过期下架、报损、冷藏温控、盘点差异调整、用户投诉退款与供应商批次结算的全链路。

技术栈：**Java 17 + Spring Boot 3.3 + Spring Security + Spring Kafka + PostgreSQL + H2(测试)**。

## 一、能力总览

| 业务问题 | 实现方式 |
| --- | --- |
| 补货记录 | 柜机编号、商品批次、保质期（生产/到期日）、数量、货道位置、温度要求、补货照片全部落表（`restock_order`/`restock_item`） |
| 重量/视觉/RFID 识别下单 | 设备事件投递 Kafka `cabinet.device.event`，服务侧按会话聚合多源事件，关门融合后 FEFO 扣批次库存、生成订单与批次台账 |
| 识别错误 | 多源数量不一致/低置信度/称重交叉校验不符 → 订单挂起 `PENDING_REVIEW`，**不扣款不扣库存**，人工复核确认/否定 |
| 拿起又放回 | 每个识别源独立计算“拿起-放回”净值，净值为 0 不出单、不扣款 |
| 货道卡货 | 设备 `CHANNEL_JAM` 事件生成告警；卡货损坏可走手工下架-报废 |
| 冷藏温度异常 | 温度越限即锁定柜机（拒绝开门），持续越限 30 分钟隔离柜内全部批次，食品安全处置（下架/报损） |
| 临期未及时下架 | 每日 02:10 定时扫描（可手动触发）生成临期/过期下架单与告警；执行下架后报废记报损 |
| 放错货道 | 上架时校验货道计划商品，不一致记 `WRONG_CHANNEL` 告警，补货单完成态为 `COMPLETED_MISMATCH` |
| 盘点库存不符 | 盘点单生成系统快照 → 录入实盘 → 差异审批调整：盘亏记报损（SHRINKAGE），台账 ADJUST_MINUS/PLUS |
| 用户误扣款 | 投诉单关联开门会话；客服证据回放后按整单/按行退款；商品收回回补库存（REFUND_RETURN），未收回记报损（REFUND_NO_RETURN） |
| 误扣款复核流程 | 复核单聚合开门/拿取/重量变化/视觉/RFID/货道盘点/扣款时间；系统初判“证据充分/疑似误判/信号冲突”，重量恢复但视觉判取走一律转人工；维持扣款可向用户展示扣款依据，确认误判则幂等退款 |
| 退款不重复 | 订单行级悲观锁 + `refund_record`/`account_txn` 双幂等键；重复点击、并发提交、设备补传重试均不会二次退款；重复申诉关联首单不二次处理 |
| 退款联动修正 | 退款后同一事务修正订单状态、会员账户余额、货道库存、批次台账、报损、识别样本（INVALIDATED 回流纠偏 / REAFFIRMED 维持） |
| 设备事件幂等/离线补传 | 设备 `eventId` 或内容指纹去重；离线恢复后集中补传的重复事件只生效一次；关门后补传识别只留存标记 lateArrival，不改原始结算 |
| 货道反复误识别 | 按货道滚动窗口（默认 7 天）统计误判：1 次 SUGGEST 观察（单次误识别），≥3 次判设备故障自动暂停货道售卖并告警安排维护；维护完成恢复 |
| 已结算订单/换机修正 | 已进入供应商已确认/已付结算周期的订单仍可退款，台账追加跨期修正不回写历史结算；货道换机旧机下线保留全部原始证据，新机同编码货道记录迁移来源 |
| 按点位调品类 | `PUT /api/channels/{id}/planned-product` 调整货道计划商品 |
| 批次全链路追溯 | `batch_ledger` 逐笔记录补货入/售卖出/下架出/报损出/盘盈盘亏/退款回库/结算；`/api/analytics/batches/{batchNo}/trace` |
| 售后证据回放 | 开门记录、多源识别结果（含视频片段 URL 与起止秒）、订单、批次库存变化、温控曲线一次返回 |
| 供应商结算 | 按周期汇总真实售出（扣除退款）与供应商责任报损，逐批次明细，应付 = 售卖成本 − 责任报损 |

### 关键状态机

- **开门会话**：`OPEN → SETTLED`（已扣款）/ `REVIEW_PENDING → SETTLED|CANCELLED`（人工复核）/ `CANCELLED`（无净拿取、温控拒开门）/ `SETTLED_REFUNDED`
- **误扣款复核单**：`AUTO_PENDING`（证据充分待确认）/ `MANUAL_REVIEW`（重量恢复但视觉判取走等疑似误判/多源冲突，必须人工）→ `UPHELD`（维持扣款并展示依据）/ `REFUNDED`（误判已退款纠正）/ `REJECTED`（驳回）；系统初判与证据快照创建即固化，后续每次修正追加到操作时间线
- **货道健康度**：`HEALTHY → SUSPECT`（窗口内首次误识别，观察）→ `SUSPENDED`（窗口内达阈值判设备故障，暂停售卖待维护）→ `MAINTAINED`（维护完成恢复）
- **商品批次**：`IN_STOCK → SOLD_OUT | REMOVED → SETTLED`
- **下架单**：`PENDING → EXECUTED`；去向 `SCRAP`（报废，产生报损）/ `RETURN_SUPPLIER`（退供，不产生报损）
- **结算单**：`DRAFT → CONFIRMED → PAID`，同供应商同周期不可重复生成

## 二、一键启动（Docker Compose）

```bash
docker compose up --build
```

启动三个容器：PostgreSQL 16（5432）、Kafka 3.9 KRaft 单节点（9092）、本应用（8080）。
应用启动时自动建表（`ddl-auto: update`）并写入种子数据。

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

## 三、本地开发启动（无 Docker）

前置：JDK 17、Maven 3.8+、PostgreSQL、Kafka（KRaft 即可）。

```bash
# 1. 准备数据库（示例为本机 5432）
createdb -U postgres cabinet
# 并通过环境变量覆盖连接信息（默认值见 application.yml）：
export CABINET_DB_URL=jdbc:postgresql://localhost:5432/cabinet
export CABINET_DB_USER=app
export CABINET_DB_PASSWORD=app
export KAFKA_BOOTSTRAP=localhost:9092

# 2. 启动应用（自动建表 + 种子数据）
mvn spring-boot:run
# 若 Kafka/PG 在自定义地址，可使用内置 local profile：
# mvn spring-boot:run -Dspring-boot.run.profiles=local   （见 application-local.yml）
```

## 四、测试账号（HTTP Basic，种子数据）

| 角色 | 用户名 | 密码 | 权限 |
| --- | --- | --- | --- |
| 补货员 | `replenisher` | `cabinet123` | 补货上架、执行下架、盘点 |
| 运营 | `operator` | `cabinet123` | 品类调整、下架、盘点审批、复核、告警、统计 |
| 财务 | `finance` | `cabinet123` | 供应商结算、查看批次损耗 |
| 客服 | `service` | `cabinet123` | 证据回放、投诉判责退款 |
| 管理员 | `admin` | `cabinet123` | 全部 |

种子主数据：供应商 `SUP001 鲜食达`、`SUP002 清泉饮品`；商品 `P-SANDWICH`（冷藏火腿三明治）、`P-MILK`（冷藏鲜牛奶）、`P-WATER`（常温矿泉水）；柜机 `CAB-001`（阳光花园社区，冷藏）、`CAB-002`（云谷科技园，常温）、`CAB-003`（市中心医院，冷藏）。另含一个次日到期的批次 `B-MILK-SEED` 用于演示临期扫描。

## 五、验证方式（核心链路）

> 以下 curl 使用 `sync=true` 同步处理设备事件，便于直接观察结果；生产柜机默认异步走 Kafka（去掉 `sync` 参数即可验证消息链路）。

### 1) 补货上架（批次 + 保质期 + 货道 + 温度 + 照片）

```bash
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/restock/arrive \
  -H 'Content-Type: application/json' \
  -d '{"cabinetNo":"CAB-001","photoUrls":"https://example.io/photos/arrive.jpg","remark":"早班补货"}'
# 返回 orderNo，例如 RS202610060001

curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/restock/RS.../items \
  -H 'Content-Type: application/json' \
  -d '{"sku":"P-MILK","batchNo":"B-MILK-1001","productionDate":"2026-10-05","expiryDate":"2026-10-12","qty":6,"channelCode":"A02","photoUrl":"https://example.io/photos/milk.jpg","cabinetTempC":4.2}'

curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/restock/RS.../complete -d '{}' \
  -H 'Content-Type: application/json'
```

放错货道演示：把 `P-MILK` 放进计划为三明治的货道 `A01`，返回 `warning=WRONG_CHANNEL`，完成后补货单状态为 `COMPLETED_MISMATCH`，`GET /api/alerts` 出现告警。

### 2) 开门购物（多源识别 + 拿起放回）→ 关门结算

```bash
# 开门
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"DOOR_OPEN","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","userCode":"U1001","userName":"王女士"}'

# 视觉识别拿起 2 瓶
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"RECOGNITION","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","channelCode":"A02","sku":"P-MILK","source":"VISION","action":"PICK","qty":2,"confidence":0.95,"videoClipUrl":"https://video.io/s1","clipStartSec":3,"clipEndSec":6}'

# RFID 同样识别 2 瓶（多源一致）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"RECOGNITION","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","channelCode":"A02","sku":"P-MILK","source":"RFID","action":"PICK","qty":2,"confidence":0.99}'

# 关门，柜内净重减少 520g（2×260g，交叉校验一致）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"DOOR_CLOSE","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","weightDeltaGram":520}'
```

结果：订单 `CHARGED` 11.00 元（2×5.50），A02 库存按批次扣减，批次台账出现 `SALE_OUT`。
若发一条 `PUT` 且与拿起数量相等，则订单 `CANCELLED`；若视觉与重量识别数量冲突，则订单 `PENDING_REVIEW`，经 `POST /api/sessions/{sessionNo}/review/confirm` 复核后才扣款。

### 3) 温控异常

```bash
# 12℃ 超过冷藏上限 8℃ → 柜机锁定
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"TEMP","cabinetNo":"CAB-001","tempC":12.0}'
# 此后 DOOR_OPEN 被拒（会话 CANCELLED，备注“温控报警锁定，开门被拒”）
```

### 4) 临期扫描 → 下架 → 报损

```bash
curl -s -u operator:cabinet123 -X POST 'http://localhost:8080/api/removals/scan-expiry?nearExpiryDays=2'
# 列出下架单（也可在告警中看到临期/过期条目），执行报废：
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/removals/RM.../execute \
  -H 'Content-Type: application/json' -d '{"disposition":"SCRAP","remark":"临期下架报废"}'
```

### 5) 盘点差异调整

```bash
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes \
  -H 'Content-Type: application/json' -d '{"cabinetNo":"CAB-001"}'
# 录入实盘（对某行少盘 1 件）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes/ST.../count \
  -H 'Content-Type: application/json' \
  -d '{"counts":[{"channelCode":"A02","batchNo":"B-MILK-1001","countedQty":3}]}'
# 审批调整（盘亏记报损 SHRINKAGE）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes/ST.../adjust \
  -H 'Content-Type: application/json' -d '{"approve":true,"remark":"监控未见异常，按盘亏处理"}'
```

### 6) 投诉 → 证据回放 → 退款

```bash
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints \
  -H 'Content-Type: application/json' \
  -d '{"sessionNo":"S-DEMO-1","category":"误扣款","description":"我拿起又放回了仍被扣费"}'

curl -s -u service:cabinet123 http://localhost:8080/api/complaints/CP.../evidence
# 一次返回：开门记录、识别事件（含视频片段）、订单明细、批次台账（库存变化）、温控记录

# 判责整单退款、商品未收回（产生 REFUND_NO_RETURN 报损）
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints/CP.../resolve \
  -H 'Content-Type: application/json' \
  -d '{"grantRefund":true,"goodsReturned":false,"resolution":"视频显示已放回，整单退款"}'
```

### 6.1) 误扣款复核（重量恢复但视觉判取走 → 转人工 → 幂等退款）

复现“拿起查看后放回仍扣款”：先造一笔关门净重变化为 0、但视觉/RFID 识别拿走 1 瓶并已扣款的订单
（商品无单件重量数据时，称重无法反证，需要复核流程把多源证据放一起判定）。

```bash
# 给会员账户充值（购物扣款 / 退款回补都有流水）
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/accounts/U1001/recharge \
  -H 'Content-Type: application/json' -d '{"amount":50.00,"remark":"复核演示充值"}'
curl -s -u service:cabinet123 http://localhost:8080/api/accounts/U1001

# 1) 用户提交误扣款投诉
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints \
  -H 'Content-Type: application/json' \
  -d '{"sessionNo":"S-REVIEW-1","category":"误扣款","description":"拿起看了看放回去了"}'

# 2) 发起复核（系统聚合开门/拿取/重量/视觉/盘点/扣款时间并给出初判）
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/reviews \
  -H 'Content-Type: application/json' \
  -d '{"complaintNo":"CP...","channelCode":"A02"}'
# 初判 REFUND_SUGGESTED/MANUAL_REVIEW：weightRestored=true 但视觉净拿取=1，转人工，不自动判定购买

# 3) 证据聚合回放（一个视图看全部传感器与时间线，而非只看某一个）
curl -s -u service:cabinet123 http://localhost:8080/api/reviews/RV.../evidence
curl -s -u service:cabinet123 http://localhost:8080/api/reviews/RV.../timeline

# 4a) 证据充分时维持扣款，并向用户展示扣款依据
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/reviews/RV.../uphold \
  -H 'Content-Type: application/json' -d '{"conclusion":"多源一致、称重匹配，扣款成立"}'
curl -s -u service:cabinet123 http://localhost:8080/api/reviews/RV.../charge-basis

# 4b) 确认误判则退款（整单/按行、商品是否收回）；重复点击同一接口不会二次退款
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/reviews/RV.../refund \
  -H 'Content-Type: application/json' \
  -d '{"goodsReturned":false,"conclusion":"重量恢复且视频显示已放回，整单退"}'
# 退款后：订单 REFUNDED、账户余额回补、货道库存（收回回补/未收回报损）、批次台账、识别样本同步修正
curl -s -u service:cabinet123 http://localhost:8080/api/accounts/U1001
curl -s -u service:cabinet123 http://localhost:8080/api/accounts/U1001/txns
```

防重与设备补传：

```bash
# 同一事件带相同 eventId 重发 -> DUPLICATE_IGNORED；关门事件重发不会二次结算
curl -s -u operator:cabinet123 -X POST 'http://localhost:8080/api/device/events?sync=true' \
  -H 'Content-Type: application/json' \
  -d '{"type":"RECOGNITION","eventId":"EVT-1","cabinetNo":"CAB-001","sessionNo":"S-1","channelCode":"A02","sku":"P-MILK","source":"VISION","action":"PICK","qty":1,"confidence":0.99}'
# 关门后才补传的识别事件 -> lateArrival=true，只留存不改原结算，复核单时间线记 LATE_DATA

# 同一会话第二次投诉 -> status=DUPLICATE，duplicateOfId 指向首单，不重复立案退款
```

货道反复误识别 → 区分单次误识别与设备故障：

```bash
curl -s -u operator:cabinet123 http://localhost:8080/api/channels/health
# 窗口（默认 7 天）内首次误判：SUSPECT 观察不停用；累计 ≥3 次：SUSPENDED 自动暂停售卖并产生 CHANNEL_SUSPENDED 告警
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/channels/CAB-001/A02/suspend \
  -H 'Content-Type: application/json' -d '{"remark":"人工暂停安排维护"}'
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/channels/CAB-001/A02/resume \
  -H 'Content-Type: application/json' -d '{"remark":"更换视觉模组，恢复"}'
# 设备换机：旧柜机下线、原始证据保留，新机同编码货道记录迁移来源
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/channels/replace-cabinet \
  -H 'Content-Type: application/json' \
  -d '{"oldCabinetNo":"CAB-001","newCabinetNo":"CAB-004","remark":"视觉模组故障换机"}'
```

### 7) 运营统计 / 批次追溯 / 供应商结算

```bash
curl -s -u operator:cabinet123 "http://localhost:8080/api/analytics/points?from=2026-10-01&to=2026-10-31"
curl -s -u operator:cabinet123 http://localhost:8080/api/analytics/batches/B-MILK-1001/trace
curl -s -u operator:cabinet123 http://localhost:8080/api/analytics/batches/B-MILK-1001/losses

# 财务按周期生成结算单（supplierId 见 GET /api/suppliers）
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/generate \
  -H 'Content-Type: application/json' \
  -d '{"supplierId":1,"start":"2026-10-01","end":"2026-10-31"}'
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/STL.../confirm
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/STL.../pay
```

### 8) Kafka 消息链路验证

设备事件默认投递 `cabinet.device.event`（3 分区，key=cabinetNo 保证柜机内有序）；
结算、退款、下架、报损、温控锁定、结算确认会发布到 `cabinet.biz.event`；处理失败重试 2 次后进入 `cabinet.device.event.DLT`。

```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic cabinet.biz.event --from-beginning
```

## 六、自动化测试

```bash
mvn test
```

- `RecognitionFusionTest`：拿起放回抵消、多源一致融合、多源冲突降级、称重交叉校验；
- `BusinessFlowIntegrationTest`（H2）：补货上架/放错货道/过期禁上架、关门结算 FEFO、拿起放回取消、存疑复核、温控锁定隔离、临期下架报损、盘点盘亏、投诉退款（收回/未收回）、供应商结算口径；
- `ReviewFlowIntegrationTest`（H2）：重量恢复但视觉判取走转人工、退款幂等回补（订单/余额/库存/样本）、证据充分维持扣款、重复申诉、设备事件去重与关门后补传、同货道反复误判自动暂停并拦截结算、维护恢复、已结算订单跨期退款修正、盘点盘盈反证、货道换机保留原始证据。

> 已执行验证：`mvn test` 全部通过（25 个用例：12 业务全链路 + 9 误扣款复核 + 4 融合判定）；`mvn package -DskipTests` 产出 `target/cabinet-restock.jar`。
> 当前隔离环境未运行 Docker 守护进程，`docker compose up --build` 的容器内联调请在具备 Docker 的环境执行（Dockerfile/compose 已随工程提供）。

## 七、主要数据模型

```
point_location 1—N cabinet 1—N channel N—1 product(计划品类)
restock_order 1—N restock_item（批次/保质期/数量/货道/温区/照片/告警）
product_batch 1—N channel_stock（货道批次库存，含隔离量；channel.suspended 暂停售卖）
product_batch 1—N batch_ledger（补货/售卖/下架/报损/盘点/退款/结算 逐笔）
door_session 1—N recognition_event（重量/视觉/RFID，含视频片段、lateArrival 补传标记）
door_session 1—1 order_record 1—N order_item（精确到批次）
complaint N—1 door_session（duplicate/duplicate_of_id 重复申诉链）
review_case 1—1 door_session（初判+证据快照不可变） 1—N review_action_log（修正时间线）
review_case 1—N sample_correction（识别样本 INVALIDATED/REAFFIRMED，原事件不删除）
order_record 1—N refund_record（idempotency_key 唯一，退款不重复）
member_account 1—N account_txn（扣款/退款回补流水，idempotency_key 唯一）
channel 1—1 channel_health_record（误判窗口/累计次数/健康度）
channel 1—N channel_maintenance_log（暂停/维护恢复/换机）
device_event_dedup（eventId/内容指纹去重，离线补传重复事件只生效一次）
removal_order 1—N removal_item；执行后按去向写 loss_record / 回退供应商
stocktake_order 1—N stocktake_item（系统数/实盘数/差异/调整标记）
settlement_batch 1—N settlement_item（批次级对账）
temp_log / alert 支撑温控与运营处置
```

可配置参数（`application.yml`）：`cabinet.review.miscount-window-hours`（误判统计窗口，默认 168=7 天）、
`cabinet.review.miscount-suspend-threshold`（窗口内自动暂停阈值，默认 3）、
`cabinet.recognition.weight-tolerance-gram`（重量恢复/交叉校验容差，默认 80g）。
