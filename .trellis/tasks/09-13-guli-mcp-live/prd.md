# 打通谷粒商城两个 MCP 工具的真实调用链路

## Goal

让 `product_detail_query`、`product_stock_query` 两个 MCP 工具在**真实数据**上跑通：从 MCP 协议 (`POST /mcp` → `tools/call`) 一路打到 guli2 的 MySQL，返回格式正确、字段真实的业务数据。

用户价值：这两个工具目前只是"能启动、能列出来"，从没在真实接口上验证过。本次要回答的唯一问题是——**它们到底能不能用**。

## Background

调查时间 2026-09-13。以下均为实测或源码证据，非推测。完整技术设计与执行步骤见 `design.md` / `implement.md`。

### 基础设施现状

| 组件 | 状态 | 证据 |
|---|---|---|
| `guli-mysql` | ❌ 容器 `Exited (1)` | 日志 `chown: changing ownership of '/var/lib/mysql/mysql.sock': Operation not permitted`；3306 无监听 |
| `guli-nacos` | ❌ 容器不存在 | `docker ps -a` 无此容器；8848 不可达 |
| `guli-redis` / `guli-rabbitmq` / `guli-minio` | ✅ 运行中 | `docker ps` |
| `ruoyi/ruoyi-nacos:2.6.2` 镜像 | ✅ 本地已存在 | `docker images`，无需构建 |
| 端口 8080 | ⚠️ 被 `rmqbroker` 占用 | `docker ps`，映射 8080-8082 |

mysql 崩溃根因：`data-home/mnt/mysql/data/mysql.sock` 是指向 `/var/run/mysqld/mysqld.sock` 的残留软链接（2026-09-03 那次运行遗留），entrypoint 的 `chown -R` 碰到它即失败退出。

### 数据现状

- `ry-cloud`、`ry-config` 等基础库**已初始化**（`data-home/mnt/mysql/data/` 下有对应目录）
- `gulimall_pms.sql` 含 `use \`ry-cloud\`;`，`pms_sku_info` 有 **26 条 INSERT**，真实主键 `sku_id=1` 为"华为 Mate 30 Pro 星河银 8GB+256GB"，价格 ¥6299
- `gulimall_wms.sql` 建了 `wms_ware_sku` / `wms_ware_info` 表但**均 0 行** → 库存表无数据

### 服务启动参数（已烘焙进 jar）

jar 于 2026-09-02 22:45 构建，`dev` profile（`activeByDefault`）已解析：

- `nacos.server = guli-nacos:8848`，账号 `nacos/nacos`
- **namespace = `dev`**（取 `spring.profiles.active`），group = `DEFAULT_GROUP`
- `guli-product` 端口 9214，`guli-ware` 端口 9215
- 两服务 datasource 均指向 `jdbc:mysql://guli-mysql:3306/ry-cloud`
- `/etc/hosts` 已把 `guli-mysql`/`guli-nacos` 等映射到 127.0.0.1

### 核心缺陷：两个工具的解析与真实契约不匹配

`mcp-server/.../GuliProductDetailMcpExecutor.java` 与 `GuliProductStockMcpExecutor.java` 的响应解析对不上 guli2 的真实返回结构：

| 工具 | executor 期望 | 真实结构（源码证据） | 后果 |
|---|---|---|---|
| 详情 | `data.skuName` / `spuName` / `brandName` / `categoryName` / `price` / `saleComment` / `images` / `saleAttrs` | 均为 `data.skuInfo.*`；图片是 `data.skuImages`，销售属性是 `data.skuItemSaleAttr`；`PmsSkuItemVo` **无** brandName/categoryName/saleComment 字段，只有 `brandId` / `catalogId` | 所有字段打印"未知"，图片与属性计数缺失 |
| 库存 | `data.rows` | `rows` 在**顶层**（`TableDataInfo` 平铺，无 `data` 包裹） | `data` 为 null → 走入 else 分支 |
| 库存 | `wareName` | `WmsWareSkuVo` **无此字段**（只有 `wareId`） | 仓库名恒为"未知" |

即：**即使服务全部起来，两个工具也不会返回正确数据。** 这是本任务的主要工作内容。

### 错误路径（源码证据）

`PmsDisplayServiceImpl.item(skuId)` 内部对 `skuInfo.getSpuId()` 的调用位于 `CompletableFuture` 中。skuId 不存在时 `skuInfo` 为 null → NPE → `CompletionException` → `BusinessException`，返回 **HTTP 200 但 `code=10002`** 的响应体。executor 不能只看 HTTP 状态码。

### 执行中发现并已实测证伪的两处早期结论

规划期的两条判断在实测中被推翻，均已按实测修正（详见 `design.md`）：

1. **"ES 缺失阻塞 guli-product 启动"——证伪。** 真实原因是 `esmapper/SkuEsMapper.java` 在提交 `9063a9a` 中被删除，导致 `BaseEsMapper` bean 缺失。与 ES 容器无关，`item()` 也从不走 ES。
2. **"绕过网关即无需 token"——证伪。** 鉴权不在网关，而在 guli-common 的 `UserInfoInterceptor`：它要求请求头带 `Authorization: Bearer <token>` 且 Redis 中存在 `guli:auth:<token>`，否则两个接口一律返回 `code=10011`。**直连微服务同样被拦。**

### 执行中发现的 guli2 master 既有缺陷（均非本任务引入）

| # | 缺陷 | 影响 | 本次处置 |
|---|---|---|---|
| 1 | `esmapper/SkuEsMapper.java` 被删（提交 `9063a9a`） | `guli-product` 无法启动 | 启动加 `--spring.main.lazy-initialization=true` 绕过，未改源码 |
| 2 | `guli-auth` 中 `PasswordLoginServiceImpl` / `EmailLoginServiceImpl` 用构造器注入 `RemoteMemberService` 却无 `@DubboReference` | `guli-auth` **无法启动**，真实登录走不通 | 改为手工向 Redis 种入等价 token，未改源码 |
| 3 | `PmsDisplayServiceImpl:306` 在 MyBatis-Plus wrapper 里用 lambda（`eq(x -> x.getSkuId(), skuId)`） | `/display/item` 恒返回 `code=10002`，详情工具拿不到任何数据 | **改 1 行**为方法引用 `eq(PmsSkuImages::getSkuId, skuId)` 并重建 guli-product（本次唯一一次改 guli2 源码，已获用户批准） |

## Requirements

- R1 修复 `guli-mysql` 并启动，MySQL 3306 可用
- R2 创建 `guli-nacos` 容器，8848 可用，`dev` namespace 配置齐备
- R3 导入 guli2 业务表结构到 `ry-cloud`
- R4 补齐库存测试数据，使库存工具可被真实数据验证
- R5 启动 `guli-product`(9214)、`guli-ware`(9215)，两者注册进 Nacos
- R6 修正两个 executor 的响应解析，使其匹配真实接口契约，并处理 `code != 200` 与空 `rows`
- R7 MCP Server 的 `guli.product.base-url` / `guli.ware.base-url` 指向 9214/9215
- R8 通过 MCP 协议 `tools/call` 端到端验证两个工具
- R9 提供可用的登录态 token，使两个工具能通过 `UserInfoInterceptor` 鉴权（`guli-auth` 不可启动，故手工种入 Redis）
- R10 修复阻断详情工具的 guli2 侧缺陷 `PmsDisplayServiceImpl:306`（1 行改动 + 重建 guli-product）

## Acceptance Criteria

- [x] AC1 MySQL 3306 可达，`ry-cloud.pms_sku_info` 有 26 行，`wms_ware_sku` 有 3-4 行
- [x] AC2 Nacos `dev` namespace 下能读到 `guli-product`、`guli-ware` 服务实例
- [x] AC3 直连 `GET :9214/display/item/1` 返回 `code=200` 且 `data.skuInfo` 非空
- [x] AC4 直连 `GET :9215/wareSku/list?skuId=1` 返回 `code=200` 且顶层 `rows` 非空
- [x] AC5 MCP `tools/call` → `product_detail_query` 返回内容中出现**真实商品名**，不再是"未知"
- [x] AC6 MCP `tools/call` → `product_stock_query` 返回内容中出现**真实库存数字与汇总行**，不再是错误/空分支
- [x] AC7 AC5/AC6 两次调用 `isError` 均为 `false`
- [x] AC8 参数非法时 `isError=true` 且提示可读：`skuId=0`（本地校验拦截）、`skuId=999999`（guli2 业务错误，须带上其 `msg`）
- [x] AC9 记录实际启动的服务清单，确认未启动网关等无关服务
- [x] AC10 未带 token 时接口返回 `code=10011`，带上 `guli.auth.token` 后返回 `code=200`（证明鉴权链路真实生效，而非被绕过）
- [x] AC11 `wms_ware_sku` 数据与库存工具输出逐行一致（120/5、40/10 等）

### 验收证据（2026-09-13 实测）

| AC | 证据 |
|---|---|
| AC1 | `pms_sku_info`=26 行，`wms_ware_sku`=4 行（sku 1→仓库1/2，sku 2→仓库1，sku 3→仓库1），`pms_sku_images`=110 行 |
| AC2 | Nacos `dev` namespace 下两服务各 1 个实例，`healthy=true`：`10.148.169.152:9214` / `:9215` |
| AC3 | `GET :9214/display/item/1` → `{"code":200,"msg":"操作成功","data":{"skuInfo":{...}}}` |
| AC4 | `GET :9215/wareSku/list?skuId=1` → `{"total":2,"rows":[...],"code":200,"msg":"查询成功"}` |
| AC5 | 工具输出含 `商品名称：华为 HUAWEI Mate 30 Pro 星河银 8GB+256GB`、`价格：￥6299.0000`、`商品图片：10 张`、`销售属性：2 项`、`规格参数：3 组` |
| AC6 | 工具输出：仓库1 总 120/锁 5/可用 115，仓库2 总 40/锁 10/可用 30，汇总 `总库存 160 件，锁定 15 件，可用 145 件` |
| AC7 | 两次调用 `isError=false`，HTTP 200 |
| AC8 | `skuId=0` → `isError=true`，`请提供有效的 SKU ID`；`skuId=999999` → `isError=true`，`商品详情查询失败：异步读取sku信息失败（code=10002）` |
| AC9 | 见下 |
| AC10 | 种 token 前 `code=10011 未登录，请先登录`；种后 `code=200` |
| AC11 | DB `wms_ware_sku`：`(1,1,120,5) (1,2,40,10) (2,1,8,0) (3,1,25,2)`，与工具输出逐行一致 |

**AC9 实际启动清单**：仅 `guli-product`(9214)、`guli-ware`(9215)、`mcp-server`(9099) 三个进程，加既有容器 `guli-mysql`(3306)、`guli-redis`(6379)、`guli-nacos`(8848)。8080 为既有 `rmqbroker` 容器占用，非本任务启动。**未启动** `ruoyi-gateway` 及其余 ruoyi/guli-mall 模块；`guli-member` 曾临时启动用于排查，已停止；`guli-auth` 从未启动（无法启动，见缺陷 2）。

## Out of Scope

- 不启动 `ruoyi-gateway`、`ruoyi-auth`、`ruoyi-system`、`ruoyi-gen`、`ruoyi-job`、`ruoyi-resource`、`ruoyi-workflow`、`ruoyi-monitor` 及其余 guli-mall 业务模块
- 不改动 RocketMQ 的 8080 端口占用
- 不做 `esearch`（ES 检索）相关验证
- 不做 Ragent bootstrap 侧的意图树对接（`IntentTreeFactory`）
- 不改动两个工具对外的 `name` / `description` / `inputSchema`（契约不变）

## Key Decisions

- **D1 验收标准为"返回真实数据"**：必须修正两个 executor 的响应解析（R6），验收看真实商品名与真实库存数字。仅"链路连通"不作为通过标准。
- **D2 库存数据用测试数据补齐**：新增 seed SQL 向 `wms_ware_sku` 插入 4 条，`sku_id` 取真实存在的 1/2/3，覆盖多仓库汇总、含锁定库存、以及"充足"与"紧张"两种库存量，使 AC6 可被真实数据验证。**仅改数据，不改 guli2 源码**——唯一的例外是 R10 那 1 行，见 D4。
- **D3 绕过网关**：MCP base-url 直连 9214/9215，不启动 `ruoyi-gateway`。网关对 `/product/**`、`/ware/**` 仅做 `StripPrefix=1`，而 executor 的 URL 拼法（`baseUrl + "/display/item/" + skuId`）恰好等价于直连路径。⚠️ 规划期曾认为此举"可绕过鉴权、无需 token"，**该结论已被实测证伪**：鉴权在 guli-common 的 `UserInfoInterceptor`，直连同样被拦（见 R9、AC10）。
- **D4 `guli-auth` 不可启动时改手工种 token，而非修 guli-auth**：`guli-auth` 缺 `@DubboReference` 是 guli2 master 既有缺陷（缺陷 2），修复它需要动 Dubbo 配置与多个类，远超"最小化实现"的边界。改为向 Redis 种入与真实登录等价的 `UserInfo`，既不改源码又能真实走通鉴权链路。
- **D5 详情工具的唯一 guli2 源码改动限于 1 行**：`PmsDisplayServiceImpl:306` 是硬阻塞（详情工具拿不到任何数据），且改动是把 MP wrapper 里的 lambda 换成方法引用，语义等价、风险极低。经用户批准执行；其余 guli2 缺陷一律用启动参数或数据绕过。

## Technical Notes

- 服务以后台进程启动，日志落 `/tmp/`：`guli-product.log`、`guli-ware.log`、`mcp-server.log`
- 可复现的验证资产放在本任务目录：`seed-ums-member.sql`、`seed-wms-ware-sku.sql`、`seed-auth-token.sh`
- MCP 端到端验证脚本：`/tmp/mcp_e2e.py`（`initialize` → `tools/list` → 6 个 `tools/call` 用例）
- 清理顺序：mcp-server → guli-product → guli-ware；容器是否停止由用户决定（保留可省下次启动时间）
- `wms_ware_info` 同样为 0 行，但库存工具不读仓库名（`WmsWareSkuVo` 无 `wareName`），故无需为它补数据
