# 商品中心 Phase 1 运行手册

## 1. 适用范围

本手册覆盖内部 SPU/SKU 主数据、品牌与类目、商品生命周期、图片、Excel 导入导出和审计记录。Phase 1 不依赖任何国内外电商平台连接器；渠道商品同步和渠道 SKU 映射属于后续阶段。

## 2. 基础依赖和环境变量

运行前需要 Java 21、Maven 3.9+、Node.js 22、pnpm 11、MySQL 8.4 和兼容 S3 API 的 MinIO。

后端必须显式配置以下变量，生产环境的密码和密钥只能从密钥管理系统注入：

| 变量 | 本地默认值 | 说明 |
| --- | --- | --- |
| `ERP_DB_URL` | `jdbc:mysql://localhost:3306/erp` | ERP MySQL JDBC 地址 |
| `ERP_DB_USERNAME` | `erp` | ERP 数据库用户 |
| `ERP_DB_PASSWORD` | `erp_local` | ERP 数据库密码 |
| `ERP_JWT_JWK_SET_URI` | `http://localhost:9000/.well-known/jwks.json` | 身份提供方 JWK 集 |
| `ERP_STORAGE_ENDPOINT` | `http://localhost:9000` | MinIO/S3 地址 |
| `ERP_STORAGE_ACCESS_KEY` | `erp_local` | 对象存储访问密钥 |
| `ERP_STORAGE_SECRET_KEY` | `erp_local_secret` | 对象存储私钥 |
| `ERP_PRODUCT_BUCKET` | `erp-products` | 商品文件桶 |

本地依赖可用以下命令启动：

```powershell
docker compose -f infra/compose.yaml up -d mysql minio
mvn -f backend/pom.xml -pl erp-boot -am -DskipTests install
mvn -f backend/erp-boot/pom.xml spring-boot:run
```

应用启动时会检查 `ERP_PRODUCT_BUCKET`，不存在时自动创建。生产账号仍应预先授予该桶的读取、写入、列举和删除权限，并禁止访问其他业务桶。

## 3. JWT 角色声明

JWT 的 `roles` 声明使用不带 `ROLE_` 前缀的业务角色：

- `PRODUCT_VIEW`：读取商品、导入导出任务和结果文件；
- `PRODUCT_OPERATOR`：包含读取能力，可编辑普通 SPU/SKU 资料；
- `PRODUCT_ADMIN`：包含商品创建、生命周期、品牌类目、图片、导入确认等管理能力。

商品管理员验收令牌至少应包含：

```json
{
  "sub": "product-admin-user-id",
  "roles": ["PRODUCT_VIEW", "PRODUCT_ADMIN"]
}
```

网关和身份服务不得把前端传入的普通请求头直接转换为角色声明。生产接口必须验证 JWT 签名、签发方、有效期和受众。

## 4. Excel 模板、字段和限制

从“商品数据中心 → 商品导入”下载当前版本模板，不要复用未知版本的本地副本。工作表第一行必须按顺序包含：

1. `SPU编码`
2. `SPU名称`
3. `品牌`
4. `类目`
5. `SKU编码`
6. `SKU名称`
7. `条码`
8. `规格JSON`
9. `计量单位`
10. `商品状态`

约束如下：

- 仅支持 `.xlsx`；
- 单文件最大 20 MiB，HTTP multipart 请求上限为 21 MB；
- 每次最多 10,000 行商品数据；
- `SPU编码`、`SPU名称`、`SKU编码`、`SKU名称`、`计量单位`和`商品状态`必填；
- `商品状态`只能是 `DRAFT`、`ACTIVE`、`DISABLED`、`ARCHIVED`；
- `规格JSON`必须是字符串键值对象，例如 `{"颜色":"蓝色","容量":"500ml"}`；
- 业务字段禁止 Excel 公式；
- SKU 编码和非空条码不得在文件内重复，也不得与其他商品冲突。

上传只创建预检任务，不写入商品主数据。运营人员必须先核对预计新增、更新、跳过、冲突和失败数量，再点击“确认执行导入”。

## 5. 导入任务恢复和安全重试

导入任务 ID 会保存在页面 URL：

```text
/products/imports?importJobId=<IMPORT_JOB_ID>
```

浏览器刷新或换班交接时使用该 URL 恢复任务。状态含义：

- `UPLOADED`：文件已接收，正在预检；
- `PREFLIGHT_READY`：预检完成，尚未写入商品；
- `CONFIRMED` / `RUNNING`：已确认或正在执行；
- `SUCCEEDED`：全部成功；
- `PARTIALLY_SUCCEEDED`：部分成功，需下载错误明细；
- `FAILED`：任务失败。

确认导入必须携带 `Idempotency-Key`。出现网关超时或响应丢失时：

1. 不要重新上传同一文件；
2. 先使用原任务 URL 查询状态；
3. 已进入 `CONFIRMED`、`RUNNING` 或终态时，不再确认；
4. 仍为 `PREFLIGHT_READY` 时，使用原幂等键重试确认；
5. 超过 30 秒没有终态，使用页面“手动刷新”，不要直接修改任务表。

`PARTIALLY_SUCCEEDED` 或 `FAILED` 且存在错误对象时，下载错误明细，修正后创建新文件和新任务。不得通过 SQL 把失败任务强制改为成功。

导出任务使用：

```text
/products/imports?mode=export&exportJobId=<EXPORT_JOB_ID>
```

导出创建失败重试时必须复用原幂等键和原筛选条件。若要修改筛选条件，应明确放弃本次重试并创建新任务。

## 6. 对象存储备份

数据库备份和商品桶备份必须来自同一恢复点。商品桶至少包含：

- `imports/<jobId>/source.xlsx` 原始导入文件；
- `imports/<jobId>/errors.xlsx` 错误明细；
- `exports/<jobId>/products.xlsx` 导出结果；
- `products/<productId>/<sha256>.<扩展名>` 商品图片对象。

备份要求：

- 开启对象版本控制或使用不可变快照；
- 保留对象 key、版本 ID、ETag/校验和和备份时间；
- 定期抽样恢复文件并验证 SHA-256；
- 恢复数据库后再恢复匹配时间点的对象数据；
- 未确认数据库中已无引用前，不得清理对象。

仅备份 MySQL 而不备份商品桶，不满足商品中心恢复要求。

## 7. 健康检查和验收

部署后依次执行：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
mvn -f backend/pom.xml verify
pnpm --dir frontend test
pnpm --dir frontend typecheck
pnpm --dir frontend build
pnpm --dir frontend --filter admin e2e product-flow.spec.ts
```

还应人工验证：

- 商品列表、新建、详情和商品数据中心页面非空；
- 管理员可以创建商品并完成生命周期变更；
- 查看角色不能执行写操作；
- 预检前后文案不会把“预计新增”误写为“已导入”；
- 部分成功可下载错误明细；
- 桌面 1536×1024 和移动 390×844 无页面级横向溢出和浏览器控制台错误。

## 8. V008 迁移和回滚规则

`V008__product_catalog.sql` 创建品牌、类目、SPU、商品图片、导入导出任务和审计表，并扩展现有 `md_sku`。迁移包含旧 SKU 到 SPU 的数据回填，不支持直接执行反向 DDL。

上线前：

1. 停止商品和订单写入；
2. 完成 MySQL 全量备份并验证可恢复；
3. 记录 `flyway_schema_history`、`md_sku` 行数和关键唯一键重复检查结果；
4. 备份商品桶；
5. 在生产数据副本执行 `mvn -f backend/pom.xml verify` 和迁移演练。

上线后健康条件：

- Flyway V008 状态为成功；
- `md_sku.spu_id`、`md_sku.name` 无空值；
- 旧 SKU 均存在对应的 `LEGACY-<id>` SPU；
- 商品读写、模板下载和对象存储健康检查通过；
- 无持续 5xx、迁移锁或唯一键冲突。

出现迁移失败、数据回填不完整、唯一键冲突、对象存储不可恢复或商品/订单写入异常时，立即停止发布。回滚只能选择：

- 应用尚未产生 V008 新写入：停止应用，恢复迁移前数据库和同恢复点商品桶，再部署旧版本；
- 已产生新写入：保留现场只读快照，使用经过演练的前向修复迁移，不得手工删除 V008 表或降级列结构。

恢复后重新核对商品、SKU、审计、导入导出任务数量，并由业务验收人确认后再开放写入。

## 9. E2E 测试数据安全

`product-flow.spec.ts` 自己启动真实 MySQL、MinIO、Spring Boot 后端和仅供本机测试的 JWK 服务，不会让订单等其他浏览器测试承担这些依赖。测试强制使用 `127.0.0.1` 上的 compose 服务、进程级临时数据库和专用 `erp-products-e2e` 桶，不继承开发机可能配置的远程数据库或对象存储地址。它使用真实签名且带 `PRODUCT_VIEW`、`PRODUCT_ADMIN` 的测试 JWT，完成商品创建、生命周期、Excel 预检及正式导入。

每次运行使用 UUID 生成独立 `E2E-<runId>` 前缀。测试开始前确认该精确前缀下没有数据，结束后只查询该前缀并通过商品状态 API 归档；任何清理请求失败都会使测试失败。禁止把前缀过滤移除后连接共享测试库或生产库，也禁止执行无条件 `DELETE`。
