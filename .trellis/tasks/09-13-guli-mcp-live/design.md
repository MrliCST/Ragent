# 技术设计：打通谷粒商城两个 MCP 工具的真实调用链路

## 架构与边界

### 目标拓扑（最少服务）

```
MCP 客户端 (curl tools/call)
        │  POST http://localhost:9099/mcp  (JSON-RPC over Streamable HTTP)
        ▼
┌─────────────────────────┐
│ ragent mcp-server :9099 │   ← 1 个 JVM，本仓库
│  ├ product_detail_query │
│  └ product_stock_query  │
└───────────┬─────────────┘
            │ 直连，不经网关；携带 Authorization: Bearer <token>
    ┌───────┴────────┐
    ▼                ▼
guli-product     guli-ware          ← 2 个 JVM，guli2 仓库
  :9214            :9215
  /display/item/{skuId}   /wareSku/list?skuId=&wareId=
    │                │
    └───────┬────────┘
            ▼
   guli-mysql :3306  (ry-cloud)     ← 1 个容器（修复既有容器）
   guli-nacos :8848  (dev ns)       ← 1 个容器（新建）
   guli-redis :6379                 ← 已运行，复用（鉴权 token 落在这里）
```

合计：**2 个新/修复容器 + 2 个 guli2 JVM + 1 个 mcp-server JVM**。

### 明确不启动

`ruoyi-gateway`、`ruoyi-auth`、`ruoyi-system`、`ruoyi-gen`、`ruoyi-job`、`ruoyi-resource`、`ruoyi-workflow`、`ruoyi-monitor`、`guli-elasticsearch`、`guli-seata`、`guli-snailjob` 以及其余 guli-mall 业务模块（含 `guli-auth`、`guli-member`）。

### 为什么能绕过网关

`ruoyi-gateway` 对 `/product/**`、`/ware/**` 的路由只做一件事：`StripPrefix=1` 去掉前缀。即：

| 网关路径 | 等价直连路径 |
|---|---|
| `:8080/product/display/item/1` | `:9214/display/item/1` |
| `:8080/ware/wareSku/list?skuId=1` | `:9215/wareSku/list?skuId=1` |

executor 的 URL 拼法本就是 `baseUrl + "/display/item/" + skuId`，因此**只改配置即可**，无需启动网关，同时规避 8080 被 `rmqbroker` 占用的问题。

### 鉴权：绕网关的代价（实测修正）

> **修正**：早期设计曾假设"直连绕过网关鉴权，测试期无需 token"。**该假设已被实测证伪**，见下。

实测结论（三条独立证据）：

1. `guli-product.jar` 与 `guli-ware.jar` 的 `BOOT-INF/lib` 中**都没有 `ruoyi-common-security`**（`pom.xml` 亦无该依赖）→ 因此**不存在** `SaInterceptor`、也不存在 `check-same-token` 的 `SaServletFilter`。
2. 证据：`GET :9214/nonexistent-xyz` 返回 guli 自己的 `{"code":404,"msg":"请求地址不存在"}`，而非同令牌过滤器的 `401 认证失败，无法访问系统资源`。
3. 唯一的守卫是 guli 自己的 `UserInfoInterceptor`（`guli-common/.../interceptor/UserInfoInterceptor.java`，由 `GuliWebMvcConfig` 注册在 `/**`），其逻辑为：

```java
String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
if (authHeader == null || !authHeader.startsWith("Bearer ")) throw new BusinessException(UNAUTHORIZED);
UserInfo userInfo = RedisUtils.getCacheObject(AuthConstant.AUTH_TOKEN_KEY_PREFIX + token); // "guli:auth:" + token
if (userInfo == null) throw new BusinessException(UNAUTHORIZED);
```

即：**必须携带 `Authorization: Bearer <token>`，且 Redis 中存在 `guli:auth:<token>` → `UserInfo`**，否则两个接口一律返回 `{"code":10011,"msg":"未登录，请先登录"}`。

**结论**：executor 当前"不带任何请求头"的写法**永远不可能调通 guli2**，与解析逻辑是否正确无关。这是 R6 之外新增的必需改动（变更 7）。

### 为什么不用"真实登录"取 token

原方案 B 是启动 `guli-auth` 走真实登录（`POST :9219/login`），但实测被两处 guli2 master 缺陷阻断，且**必须改源码**，与 D2 冲突：

- `PasswordLoginServiceImpl:39` 与 `EmailLoginServiceImpl:36` 都把 `RemoteMemberService` 写成普通构造注入字段，**全模块 0 处 `@DubboReference`**（`grep` 全量确认），而正确写法见 `PmsSpuServiceImpl:72-77`。
- 后果：`guli-auth` 启动即 `No qualifying bean of type 'com.atlearn.guli.RemoteMemberService'`。
- 且 `guli-auth` 的登录依赖 Dubbo `RemoteMemberService`，即使补上注解还需常驻 `guli-member`，即 **+2 个 JVM**。

故改用 **变更 8：向 Redis 直接种入测试 token**，0 个额外服务。

## guli2 master 既有缺陷（本次实测发现，均为上游问题，非本任务引入）

| # | 位置 | 症状 | 本次处置 |
|---|---|---|---|
| 1 | `esmapper/SkuEsMapper.java` 在提交 `9063a9a` 被删除 | `PmsSpuServiceImpl` 注入 `BaseEsMapper<SkuEsModel>` 找不到 bean → `guli-product` 启动失败 | 不改源码，改用 `--spring.main.lazy-initialization=true` 启动 |
| 2 | `guli-auth` 两处缺 `@DubboReference` | `guli-auth` 无法启动 | 不改源码，改走变更 8 |
| 3 | `PmsDisplayServiceImpl:306` 用 lambda `x -> x.getSkuId()` 而非方法引用 | MyBatis-Plus 无法从 lambda 解析属性名（`getImplMethodName()` 得到合成方法名 `lambda$item$ee7ea665$1`）→ `/display/item/{skuId}` **恒返 `10002 异步读取sku信息失败`** | **破例修 1 行**（变更 9，已获用户批准） |

> 缺陷 1 同时说明：**ES 并不是 guli-product 的启动阻塞点**。早期 R1 判定树（围绕 easy-es 连通性）针对的是错误方向；`item()` 路径本身也不依赖 ES。

## 数据流与契约

### 契约 A：商品详情（已核实结构 + 已修复可用性）

`GET :9214/display/item/{skuId}` → `R<PmsSkuItemVo>`：

```json
{ "code": 200, "msg": "操作成功",
  "data": {
    "skuInfo":       { "skuId":1, "spuId":11, "skuName":"华为 ...", "skuTitle":"...",
                       "skuSubtitle":"...", "price":6299.0000, "skuDefaultImg":"...",
                       "catalogId":225, "brandId":9, "saleCount":0 },
    "skuImages":     [ { "skuId":1, "imgUrl":"...", "imgSort":0, "defaultImg":1 } ],
    "skuItemSaleAttr": [ { "attrId":.., "attrName":"颜色", "attrValues":["星河银"] } ],
    "spuInfoDesc":   { "spuId":11, "decript":[...] },
    "spuBaseAttrGroup": [ { "groupName":"...", "attrs":[...] } ]
  } }
```

**注意**：`PmsSkuItemVo` **没有** `brandName` / `categoryName` / `saleComment` 字段，只有 `brandId`(9) / `catalogId`(225)。现有 executor 输出"品牌/分类/描述"三项在这份契约里**无对应字段**，必须改为输出真实存在的字段（或退化为输出 id）。

### 契约 B：商品库存（已实测通过）

`GET :9215/wareSku/list?skuId={skuId}[&wareId={wareId}]` → `TableDataInfo<WmsWareSkuVo>`，**顶层平铺，无 `data` 包裹**：

```json
{ "total": 2, "code": 200, "msg": "查询成功",
  "rows": [ { "id":1, "skuId":1, "wareId":1, "stock":120, "skuName":"华为 ...", "stockLocked":5 },
            { "id":2, "skuId":1, "wareId":2, "stock":40,  "skuName":"华为 ...", "stockLocked":10 } ] }
```

**注意**：`WmsWareSkuVo` **没有 `wareName` 字段**，只有 `wareId`。

### 契约 C：错误路径（已核实）

`item(skuId)` 内部对 `skuInfo.getSpuId()` 的调用位于 `CompletableFuture` 中。**skuId 不存在时 `skuInfo` 为 null → NPE → `CompletionException` → `BusinessException(ASYNC_READ_SKUITEM_FAILED=10002)`**，返回 **HTTP 200 但 `code=10002`** 的 `R`。executor 必须处理"HTTP 200 但 code≠200"这一情形，不能只看状态码。

## 变更设计

### 变更 1：修 guli-mysql（运维操作，无代码改动）✅ 已完成

根因是残留软链接 `data-home/mnt/mysql/data/mysql.sock` → `/var/run/mysqld/mysqld.sock` 使 entrypoint 的 `chown -R` 失败。

```
删除该软链接 → docker start guli-mysql（数据卷保留，不动数据）
```

回滚：无破坏性操作，数据目录只删一个失效软链接。

### 变更 2：建 guli-nacos（运维操作）✅ 已完成

镜像 `ruoyi/ruoyi-nacos:2.6.2` 本地已存在。用 `script/docker-compose-infra.yml` 的 `nacos` 服务启动，`--no-deps` 避免连带重启 mysql。

配置落在 **namespace `dev`**、group `DEFAULT_GROUP`（由 jar 内烘焙的 `spring.profiles.active=dev` 决定）。实测该 namespace 已有 13 份配置。

### 变更 3：导入业务表（运维操作）✅ 已完成

`config/business-sql-config/gulimall_pms.sql`（含 `use \`ry-cloud\`;`）与 `gulimall_wms.sql` 已导入 `ry-cloud`。

### 变更 4：新增库存 seed SQL（新文件）✅ 已完成

`.trellis/tasks/09-13-guli-mcp-live/seed-wms-ware-sku.sql`，插入 4 条：

| sku_id | ware_id | stock | stock_locked | 覆盖意图 |
|---|---|---|---|---|
| 1 | 1 | 120 | 5 | 多仓库汇总的第一条 + "充足"分支（可用 115 > 30） |
| 1 | 2 | 40 | 10 | 同 sku 多仓库汇总（合计 160，可用 145） |
| 2 | 1 | 8 | 0 | "紧张"分支（可用 8 ≤ 10） |
| 3 | 1 | 25 | 2 | "较少"分支（可用 23，落在 10-30） |

`sku_id` 全部取 `pms_sku_info` 中真实存在的主键。仅加数据，不改 guli2 源码。回滚：`DELETE FROM wms_ware_sku WHERE sku_id IN (1,2,3);`

### 变更 5：修正两个 executor 的响应解析（核心代码改动）

涉及文件：
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/GuliProductDetailMcpExecutor.java`
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/GuliProductStockMcpExecutor.java`

**详情工具** `buildProductDetailResult` 改为读取真实路径：

| 输出项 | 新取值路径 |
|---|---|
| 商品名称 | `data.skuInfo.skuName` |
| 标题 | `data.skuInfo.skuTitle` |
| 副标题/卖点 | `data.skuInfo.skuSubtitle` |
| 价格 | `data.skuInfo.price` |
| 默认图 | `data.skuInfo.skuDefaultImg` |
| 图片数量 | `data.skuImages` 数组长度 |
| 销售属性 | 遍历 `data.skuItemSaleAttr`，输出 `attrName` + `attrValues` |
| 商品介绍 | `data.spuInfoDesc` |
| 规格参数 | 遍历 `data.spuBaseAttrGroup` |
| 品牌/分类 | 改为输出 `skuInfo.brandId` / `skuInfo.catalogId`（真实字段），**删除原虚构的 brandName/categoryName/saleComment 三项** |

**库存工具** `buildStockResult` 改为读顶层 `rows`：

| 输出项 | 新取值路径 |
|---|---|
| 记录数 | `total` / `rows` 长度 |
| 各仓库明细 | `rows[].wareId`（**不再读 `wareName`**）、`stock`、`stockLocked`、可用 = stock − stockLocked |
| 汇总 | 累加各行 `stock` / `stockLocked` |
| 状态提示 | 沿用现有阈值逻辑（≤0 缺货 / ≤10 紧张 / ≤30 较少 / >30 充足） |

**错误处理增强**：两者均需在 HTTP 200 时进一步检查响应体 `code` 字段；`code != 200` 时返回 `isError=true` 并带上 `msg`（覆盖契约 C 的 10002 情形）。

**保持不动的部分**：工具 `name`、`description`、`inputSchema`、参数校验（`skuId <= 0` 拒绝）、超时设置、`McpServerConfig` 的注册方式。即工具对外契约不变，只改内部解析。

### 变更 6：MCP Server 配置（配置改动）

`mcp-server/src/main/resources/application.yml`：

```yaml
guli:
  product:
    base-url: http://localhost:9214   # 原 http://localhost:8080/product
  ware:
    base-url: http://localhost:9215   # 原 http://localhost:8080/ware
  auth:
    token: mcp-live-test-token-0001   # 变更 8 种入 Redis 的测试 token
```

### 变更 7：executor 携带 Authorization 头（新增必需改动）

两个 executor 在发请求时统一加上：

```
Authorization: Bearer ${guli.auth.token}
```

实现方式：`GuliMcpProperties` 增加 `auth.token` 字段，executor 通过 `HttpHeaders` 注入。**这是任何真实部署都必须具备的能力**——guli2 的接口一律要求登录态，不带 token 的工具在架构上就不可能可用。

若 `token` 未配置则不加该头（保持向后兼容，此时行为等同现状）。

### 变更 8：向 Redis 种入测试 token（数据操作，替代真实登录）

```
SET guli:auth:mcp-live-test-token-0001 \
    '["com.atlearn.guli.core.UserInfo",{"userId":1,"userKey":"mcp-live-test-token-0001","isTempUser":false}]' \
    EX 2592000
```

格式依据（`ruoyi-common-redis/.../RedisConfiguration.java:58`）：

```java
om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
TypedJsonJacksonCodec jsonCodec = new TypedJsonJacksonCodec(Object.class, om);
CompositeCodec codec = new CompositeCodec(StringCodec.INSTANCE, jsonCodec, jsonCodec);
```

`DefaultTyping.NON_FINAL` 默认采用 `WRAPPER_ARRAY` 风格 → 值为 `["全类名", {字段}]`。`KeyPrefixHandler` 在 keyPrefix 为空时不加前缀（现有 `Authorization:login:token:*` 键无前缀即为佐证）。

**该格式已一次命中通过实测**（`guli:auth:*` 原本 0 个键，写入后 `10011` 立即变为正常响应），无需迭代。

**性质说明**：这是**测试态身份**，不是伪造凭据——数据路径（MCP → HTTP → guli-product/ware → MySQL）完全真实，仅登录态由人工种入。回滚：`DEL guli:auth:mcp-live-test-token-0001`。

### 变更 9：修 guli2 上游缺陷 3（1 行源码改动，已获用户破例批准）

`PmsDisplayServiceImpl.java:306`：

```diff
- Wrappers.<PmsSkuImages>lambdaQuery().eq(x -> x.getSkuId(), skuId));
+ Wrappers.<PmsSkuImages>lambdaQuery().eq(PmsSkuImages::getSkuId, skuId));
```

依据：全仓 MP wrapper 用法中 **25 处用方法引用、仅 3 处用 lambda**，此写法属异常；且这 3 处中只有本处在 `item()` 调用路径上。改后需重建 `guli-product.jar`。

回滚：`git checkout guli-mall/guli-product/src/main/java/com/atlearn/guli/service/impl/PmsDisplayServiceImpl.java`

### 变更 10：guli-ware 缺 Nacos 配置的临时补齐（启动参数，不写共享配置）

Nacos `dev` namespace **只有 `guli-product.yml`，没有 `guli-ware.yml`**（实测 13 份配置清单确认），导致 `dynamic-datasource can not find primary datasource` 启动失败。

```
--spring.config.additional-location=file:/tmp/guli-ware-datasource.yml
```

内容与 `guli-product.yml` 等价（`primary: master` + master 数据源，引用 `${datasource.system-master.*}`）。**刻意不写入 Nacos**，避免改动共享配置状态；该缺口作为发现记录在案。

## 兼容性与回滚

- **对外契约不变**：两个工具的 name/description/inputSchema 不动，Ragent bootstrap 侧的 `McpClientAutoConfiguration` 无需改动，工具清单仍是 7 个。
- **配置可回退**：`base-url` 改回 8080 即恢复走网关（前提是网关已起且 8080 空闲）；`guli.auth.token` 置空即回到不带鉴权头的旧行为。
- **数据可回退**：seed SQL 用对应 `DELETE` 撤销；种入的 token 用 `DEL` 撤销；导入的业务表不删（只增）。
- **代码可回退**：两个 executor 的改动局限在 `buildProductDetailResult` / `buildStockResult` 及错误分支，单文件 `git checkout` 即可回滚；guli2 的 1 行改动同样单文件可回滚（回滚后需重新构建 jar）。

## 风险与预案（实测后更新）

| 风险 | 状态 | 结论 |
|---|---|---|
| ~~R1 guli-product 被 ES 阻塞启动~~ | **已证伪** | 真实阻塞是缺陷 1（`SkuEsMapper` 被删导致 `BaseEsMapper` bean 缺失）；ES 无关。以 `--spring.main.lazy-initialization=true` 绕过，**未启动 ES，未加服务** |
| R2 Nacos `dev` namespace 或配置缺失 | 已排除 | 13 份配置齐备 |
| R3 guli-ware 依赖 Dubbo 注册 | 已排除 | 启动成功（21.87s），无需关闭 Dubbo |
| R4 `pms_sku_info` 为空 | 已排除 | 26 行 |
| R5 端口 9214/9215 被占 | 已排除 | 均空闲 |
| **R6 鉴权拦截（新增）** | **已解决** | 见"鉴权：绕网关的代价"与变更 8，格式一次命中 |
| **R7 guli-ware 缺 Nacos 配置（新增）** | **已解决** | 变更 10，启动参数补齐，不写共享配置 |
| **R8 `/display/item` 恒返 10002（新增）** | **已解决** | 缺陷 3 + 变更 9，1 行修复后重建 |
| R9 重建 guli-product 影响运行中 JVM | 已规避 | 重建前先停 guli-product，避免 Maven 覆盖正在惰性读取的 fat jar |

## 运维说明

- 所有服务以后台进程启动，日志分别落 `/tmp/guli-product.log`、`/tmp/guli-ware.log`、`/tmp/mcp-server.log`；构建日志 `/tmp/guli-product-build.log`
- 验证结束后按 prd.md AC9 记录实际启动的服务清单
- 清理顺序：mcp-server → guli-product → guli-ware → （容器按用户意愿保留或停）
- 临时启动过的 `guli-member`（登录方案调研期）已停止；`guli-auth` 从未成功启动
