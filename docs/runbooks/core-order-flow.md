# 核心订单链路运行手册

## 目标与边界

本手册覆盖平台订单进入统一订单模型、库存预占、履约单创建、Outbox 投递、运营工作台查询，以及数据库备份恢复验证。首期连接器边界为微信视频号小店、天猫、淘宝、京东、抖音、小红书、快手小店、得物和拼多多。

## 本地启动

前置环境：Java 21、Maven、Node.js 22、pnpm 11、Docker Desktop。

```powershell
docker compose -f infra/compose.yaml up -d mysql redis namesrv broker minio

$env:ERP_DB_URL='jdbc:mysql://localhost:3306/erp'
$env:ERP_DB_USERNAME='erp'
$env:ERP_DB_PASSWORD='erp_local'
$env:ERP_JWT_JWK_SET_URI='https://identity.example.com/.well-known/jwks.json'
mvn -f backend/pom.xml -pl erp-boot -am -DskipTests install
mvn -f backend/erp-boot/pom.xml spring-boot:run

cd frontend
pnpm install --frozen-lockfile
$env:VITE_DEMO_MODE='true'
pnpm dev
```

本地运营工作台位于 `http://localhost:54575/orders`。演示模式使用脱敏且固定的订单数据；联调时删除 `VITE_DEMO_MODE`，前端会请求 `/api/orders`。

## 健康与指标

- 健康检查：`GET /actuator/health`
- Prometheus 指标：`GET /actuator/prometheus`（需由监控系统携带受信任 JWT，或在网关层限制为监控网段）
- `erp_order_ingestion_total`：统一订单接收总数
- `erp_order_ingestion_lag_seconds`：平台付款到 ERP 接收的最近延迟
- `erp_inventory_reservation_failure_total`：库存预占失败总数
- `erp_outbox_pending`：待投递 Outbox 数量

健康端点不展示详细组件信息，日志关联字段只允许 `traceId`、ERP `orderId` 和平台代码，禁止记录令牌、完整收件信息、密钥或平台原始报文。

建议初始告警：

- 订单接收延迟连续 10 分钟大于 300 秒。
- 预占失败率 15 分钟内超过订单量的 5%。
- 待投递 Outbox 连续 10 分钟大于 1,000。
- 健康检查连续 3 次失败。

## 验收命令

```powershell
$env:TESTCONTAINERS_RYUK_DISABLED='true'
mvn -B -f backend/pom.xml verify

cd frontend
pnpm install --frozen-lockfile
pnpm test
pnpm typecheck
pnpm build
pnpm --filter admin e2e
```

必须同时验证：重复导入不重复建单、并发扣减不产生负库存、强制回滚不留下订单/预占/履约/Outbox 数据、越权查询被拒绝、收件信息按权限脱敏。

## 备份恢复演练

先确保 MySQL 已启动且 Flyway 已创建业务表，再运行：

```powershell
docker compose -f infra/compose.yaml up -d mysql
powershell -File infra/scripts/backup-restore-smoke.ps1
```

脚本把 `erp` 备份到临时文件，恢复到隔离的 `erp_restore_smoke` 数据库，确认至少恢复一张表后删除该测试库和临时文件，不会删除当前 `erp` 数据库。生产环境必须从密钥管理系统注入 `ERP_DB_USERNAME`、`ERP_DB_PASSWORD`、`ERP_DB_ROOT_PASSWORD` 和 `ERP_DB_NAME`。

## 故障处置

1. 暂停受影响店铺的订单拉取游标推进，保留原始报文和平台请求 ID。
2. 查询订单号、平台单号、`traceId` 与 Outbox 事件 ID，确认失败发生在归一化、库存、履约还是投递阶段。
3. 库存不足进入异常订单队列，不手工绕过库存台账。
4. Outbox 堆积时先修复消息代理或消费者，再使用幂等事件 ID 重放；不得直接补写下游业务表。
5. 数据库恢复前停止写入，记录恢复点并保留原库只读快照；恢复后依次核对订单、预占、履约和 Outbox 数量。
6. 恢复平台拉取时从已确认游标继续，并用幂等键重放重叠时间窗。

## 回滚标准

出现负库存、跨店铺数据泄漏、无法解释的订单丢失、重复履约或备份无法恢复时，停止发布并回滚应用版本。数据库变更只允许使用经过验证的前向修复脚本；不得在未备份的情况下手工回退生产表结构。
