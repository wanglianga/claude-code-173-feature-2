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
| 误扣款复核流程 | 复核工作台一屏聚合开门、拿取、重量变化、摄像头识别、货道盘点、扣款时间；系统自动研判：重量已恢复但视觉仍认为取走 → 不直接判定购买、转人工；多源一致且重量/库存齐减 → 证据充分，可向用户展示扣款依据 |
| 退款同步修正 | 复核退款后订单状态、账户余额（钱包流水）、货道库存、识别样本（MISJUDGED 标注）同步修正；状态机 + 钱包幂等键 `(业务,单据,类型)` 双保险，重复点击/重复申诉/设备补传均不二次退款 |
| 设备事件幂等 | 设备事件 `eventId` 唯一去重，重复投递只计数不处理；设备离线后集中补传的迟到事件仅存档打标（backfill），不触发二次结算 |
| 货道健康监控 | 同一货道窗口期内误扣退款投诉达预警次数仅告警（单次误识别），达故障阈值判定疑似设备故障：自动暂停货道（禁止上架）+ 自动派检查工单 + CRITICAL 告警；支持检查/维修/换机，换机记录新旧设备序列号 |
| 复核审计 | 原始系统研判、人工判责、重复申诉、设备补传、货道暂停/换机、已结算订单修正全程只追加留痕（`review_audit`） |
| 按点位调品类 | `PUT /api/channels/{id}/planned-product` 调整货道计划商品 |
| 批次全链路追溯 | `batch_ledger` 逐笔记录补货入/售卖出/下架出/报损出/盘盈盘亏/退款回库/结算；`/api/analytics/batches/{batchNo}/trace` |
| 售后证据回放 | 开门记录、多源识别结果（含视频片段 URL 与起止秒）、订单、批次库存变化、温控曲线一次返回 |
| 供应商结算 | 按周期汇总真实售出（扣除退款）与供应商责任报损，逐批次明细，应付 = 售卖成本 − 责任报损 |

### 关键状态机

- **开门会话**：`OPEN → SETTLED`（已扣款）/ `REVIEW_PENDING → SETTLED|CANCELLED`（人工复核）/ `CANCELLED`（无净拿取、温控拒开门）/ `SETTLED_REFUNDED`
- **商品批次**：`IN_STOCK → SOLD_OUT | REMOVED → SETTLED`
- **下架单**：`PENDING → EXECUTED`；去向 `SCRAP`（报废，产生报损）/ `RETURN_SUPPLIER`（退供，不产生报损）
- **结算单**：`DRAFT → CONFIRMED → PAID`，同供应商同周期不可重复生成
- **投诉单**：`SUBMITTED → RESOLVED_REFUNDED | RESOLVED_REJECTED`（结单后重复判责幂等忽略）
- **货道**：`NORMAL ⇄ SUSPENDED`（反复误扣投诉自动暂停，维护完成恢复）
- **维护工单**：`OPEN → IN_PROGRESS → DONE`（检查/维修/换机，换机留新旧设备 SN）
- **识别样本**：`NONE → CONFIRMED | MISJUDGED`（复核驳回/退款时同步修正）

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
演示用户钱包：`U1001`（余额 100.00）、`U1002`（余额 50.00）——关门结算从余额扣款，复核退款退回余额，见 `GET /api/wallets/{userCode}`。

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

结果：订单 `CHARGED` 11.00 元（2×5.50），同时从用户钱包余额扣款（`GET /api/wallets/U1001` 可见 CHARGE 流水），A02 库存按批次扣减，批次台账出现 `SALE_OUT`。
若发一条 `PUT` 且与拿起数量相等，则订单 `CANCELLED`；若视觉与重量识别数量冲突、或重量已恢复但识别仍认为取走，则订单 `PENDING_REVIEW`（不扣款），经 `POST /api/sessions/{sessionNo}/review/confirm` 复核后才扣款。

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

### 6b) 误扣款复核流程（证据聚合 + 系统研判 + 幂等退款）

场景：用户拿起饮料查看后放回，重量已恢复（关门净重 0），但视觉仍认为被取走。
此时融合结算不会直接扣款，而是挂起 `PENDING_REVIEW` 转人工；若人工误确认扣款，
用户投诉后进入复核流程：

```bash
# 开门 + 视觉误判拿起 1 瓶 + 关门净重 0（已放回）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"DOOR_OPEN","cabinetNo":"CAB-001","sessionNo":"S-REV-1","userCode":"U1001","userName":"王女士","eventId":"E-OPEN-1"}'
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"RECOGNITION","cabinetNo":"CAB-001","sessionNo":"S-REV-1","channelCode":"A02","sku":"P-MILK","source":"VISION","action":"PICK","qty":1,"confidence":0.95,"videoClipUrl":"https://video.io/r1","clipStartSec":2,"clipEndSec":5,"eventId":"E-REC-1"}'
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"DOOR_CLOSE","cabinetNo":"CAB-001","sessionNo":"S-REV-1","weightDeltaGram":0,"eventId":"E-CLOSE-1"}'
# -> 重量已恢复但视觉认为取走：订单 PENDING_REVIEW，不扣款

# 人工误确认 -> 扣款 5.50（钱包余额 100 -> 94.50）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/sessions/S-REV-1/review/confirm \
  -H 'Content-Type: application/json' -d '{"note":"按视觉结果结算"}'
curl -s -u service:cabinet123 http://localhost:8080/api/wallets/U1001

# 用户投诉 -> 系统研判 SUSPECT_MISCHARGE（重量已恢复但视觉认为取走，转人工）
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints \
  -H 'Content-Type: application/json' \
  -d '{"sessionNo":"S-REV-1","category":"误扣款","description":"拿起看了下就放回，仍被扣费"}'

# 复核证据聚合视图：开门/拿取/重量变化/视觉识别/货道盘点/扣款时间 + 系统研判 + 审计轨迹
curl -s -u service:cabinet123 http://localhost:8080/api/review/CP.../evidence

# 判责退款（商品已放回=已收回）：订单 REFUNDED、钱包退回 5.50、A02 库存回补、
# 视觉识别样本标记 MISJUDGED；重复提交本请求不会二次退款
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/review/CP.../resolve \
  -H 'Content-Type: application/json' \
  -d '{"grantRefund":true,"goodsReturned":true,"resolution":"重量已恢复，视觉误判，整单退款"}'

# 审计轨迹 / 待复核队列 / 误识别样本 / 用户侧扣款依据
curl -s -u service:cabinet123 http://localhost:8080/api/review/CP.../audits
curl -s -u service:cabinet123 http://localhost:8080/api/review/queue
curl -s -u operator:cabinet123  http://localhost:8080/api/review/samples/misjudged
curl -s -u service:cabinet123 http://localhost:8080/api/review/charge-basis/OD...
```

幂等与补传验证：

```bash
# 重复投递同一 eventId：只处理一次，重复次数累计在设备事件日志
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"DOOR_CLOSE","cabinetNo":"CAB-001","sessionNo":"S-REV-1","weightDeltaGram":0,"eventId":"E-CLOSE-1"}'
# 设备离线恢复后补传识别事件（会话已结束）：仅存档打标 backfill，不触发二次结算/退款
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"RECOGNITION","cabinetNo":"CAB-001","sessionNo":"S-REV-1","channelCode":"A02","sku":"P-MILK","source":"VISION","action":"PICK","qty":1,"confidence":0.9,"eventId":"E-REC-LATE","backfill":true}'
# 重复申诉：保留记录并关联首单，再次判责退款金额为 0
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints \
  -H 'Content-Type: application/json' \
  -d '{"sessionNo":"S-REV-1","category":"误扣款","description":"再次申诉"}'
```

### 6c) 货道健康：反复误扣 → 暂停货道 → 维护/换机

同一货道窗口期（默认 7 天）内误扣退款投诉达 2 次 WARN 告警（单次误识别），
达 3 次判定疑似设备故障：自动暂停货道（禁止上架）+ 自动派检查工单 + CRITICAL 告警。

```bash
# 货道健康总览（各货道窗口内误扣退款投诉数/状态/在途工单）
curl -s -u operator:cabinet123 http://localhost:8080/api/channels/health

# 维护工单：列表 / 人工建单 / 开始 / 完成（REPLACE 换机须填新设备序列号）
curl -s -u operator:cabinet123 "http://localhost:8080/api/maintenance?status=OPEN"
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/maintenance/MO.../start
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/maintenance/MO.../complete \
  -H 'Content-Type: application/json' \
  -d '{"resolution":"整模块更换","newEquipmentSn":"EQ-NEW-2026"}'
# 工单完成后暂停货道自动恢复；换机新旧 SN 留痕，货道历史投诉/识别/订单记录保留

# 人工暂停/恢复货道
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/channels/{id}/suspend \
  -H 'Content-Type: application/json' -d '{"reason":"用户集中投诉，先停售检查"}'
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/channels/{id}/resume \
  -H 'Content-Type: application/json' -d '{"note":"检修完成"}'
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

- `RecognitionFusionTest`：拿起放回抵消、多源一致融合、多源冲突降级、称重交叉校验、重量已恢复但视觉认为取走转人工；
- `BusinessFlowIntegrationTest`（H2）：补货上架/放错货道/过期禁上架、关门结算 FEFO、拿起放回取消、存疑复核、温控锁定隔离、临期下架报损、盘点盘亏、投诉退款（收回/未收回）、供应商结算口径；
- `ReviewFlowIntegrationTest`（H2）：误扣款复核全链路（证据聚合研判、退款后订单/余额/库存/样本同步修正、重复点击幂等）、设备重复投递与离线补传去重、重复申诉不二次退款、货道健康（3 次误扣自动暂停+派单+换机留痕）、证据充分驳回与扣款依据、钱包充退幂等。

## 七、主要数据模型

```
point_location 1—N cabinet 1—N channel N—1 product(计划品类)
restock_order 1—N restock_item（批次/保质期/数量/货道/温区/照片/告警）
product_batch 1—N channel_stock（货道批次库存，含隔离量）
product_batch 1—N batch_ledger（补货/售卖/下架/报损/盘点/退款/结算 逐笔）
door_session 1—N recognition_event（重量/视觉/RFID，含视频片段、补传标记、样本判定）
door_session 1—1 order_record 1—N order_item（精确到批次）
removal_order 1—N removal_item；执行后按去向写 loss_record / 回退供应商
stocktake_order 1—N stocktake_item（系统数/实盘数/差异/调整标记）
complaint N—1 door_session；settlement_batch 1—N settlement_item（批次级对账）
wallet_account 1—N wallet_txn（扣款/退款流水，(业务,单据,类型) 唯一幂等）
review_audit（复核审计：原始研判/判责/重复申诉/补传/货道处置/已结算修正）
maintenance_order（货道检查/维修/换机工单，新旧设备 SN 留痕）
device_event_log（设备事件按 eventId 去重，补传打标，重复计数）
temp_log / alert 支撑温控与运营处置
```
