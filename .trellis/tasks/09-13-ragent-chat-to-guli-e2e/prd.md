# 打通 Ragent 对话 → 谷粒商城 MCP 工具的完整链路

## Goal

让用户在 Ragent 里用自然语言提问（如"华为 Mate 30 Pro 多少钱"），请求能经意图识别路由到 `product_detail_query` / `product_stock_query`，真实调用谷粒商城并返回库里的真实数据。

这是父任务 `09-13-guli-mcp-live` 的最后一公里。父任务已证明 **mcp-server 的两个工具 → guli2 → MySQL** 这段是真实可用的（证据见父任务 `evidence-mcp-e2e.txt`）；本任务补的是它下游的 **对话 → 意图路由 → MCP 工具** 这一段。

## Background

调查时间 2026-09-13，均为实测或源码证据。

### 已经就绪的部分

| 环节 | 状态 | 证据 |
|---|---|---|
| mcp-server 两个工具 → guli2 → MySQL | ✅ 已真实打通 | 父任务 6 个 `tools/call` 用例全通过 |
| bootstrap 的 MCP 客户端指向 mcp-server | ✅ 已配置 | `bootstrap/src/main/resources/application.yaml:137-140`，name `default`，url `http://localhost:9099`；绑定类 `McpClientProperties` 前缀 `rag.mcp`（`mcp:` 缩进 2 空格，是 `rag:` 的子键，**不是** `ragent:` 的） |
| mcp-server 进程 | ✅ 运行中 | `:9099`，PID 68002，7 个工具含本任务要用的两个 |
| LLM 供应商 | ✅ 可用 | 默认 provider `bailian` / `qwen-max`（`application.yaml:45-46`），`BAILIAN_API_KEY` 环境变量已设置 |
| 基础设施 | ✅ 运行中 | `ragent-pg`、`guli-redis`（Ragent 也用它：`127.0.0.1:6379`，密码 `ruoyi123`）、`rmqbroker`/`rmqnamesrv`、`rustfs` |

### 缺口：意图树里没有谷粒商城的节点

```
t_intent_node 现有内容（8 行，全部 description 与 examples 为空）：
 intent_code  | parent_code | level | kind |  mcp_tool_id
--------------+-------------+-------+------+---------------
 busyness     |             |     0 |    0 |
 chat         |             |     0 |    0 |
 chat-free    | chat        |     1 |    0 |
 sale         | busyness    |     1 |    0 |
 weather      | chat        |     1 |    2 | weather_query
 work         | busyness    |     1 |    0 |
 show         | work        |     2 |    0 |
 weather-load | weather     |     2 |    0 | (deleted=1)
```

`kind=2` 即 `IntentKind.MCP`。**没有任何一行指向 `product_detail_query` / `product_stock_query`**，所以意图路由不会命中这两个工具，用户提问走不到。

### 关键机制（源码证据）

1. **意图识别是"把全部叶子节点一次性喂给 LLM 打分"**，不是向量匹配：
   `DefaultIntentClassifier.classifyTargets()` → `buildPrompt(leafNodes)`，逐节点渲染
   `id`(=intent_code) / `path` / `description` / `type` / `toolId` / `examples`。
   → **`description` 和 `examples` 会直接进 Prompt，是路由准确率的主要抓手。**
2. **只有叶子节点参与识别**：`listMcpToolNodes()` 过滤 `leafNodes` + `isMCP()`。
   → MCP 节点不能有子节点。
3. **意图树走 Redis 缓存，且只在缓存为空时才回落到 DB**：
   `DefaultIntentClassifier.loadIntentTreeData()` 先 `getIntentTreeFromCache()`，非空就直接用。
   Key = `ragent:intent:tree`（`IntentTreeCacheManager:45`），**该 key 当前已存在于 Redis**。
   → **直接写 SQL 插节点不会生效**，必须让缓存失效。
4. **管理 API 写后会清缓存**：`IntentTreeController` 提供 `POST /intent-tree`（无鉴权注解），
   其实现 `IntentTreeServiceImpl:174` 调用 `intentTreeCacheManager.clearIntentTreeCache()`。
   → **走 API 建节点是正确路径**，SQL 直插需自行删 Redis key。

### `MCP_INTEGRATION_GUIDE.md` 里的现成 SQL 不可用

`MCP_INTEGRATION_GUIDE.md:276-296` 附了"数据库初始化脚本"，但**表名和列名全错，从未成功执行过**（DB 里确实没有）：

| 草稿 SQL | 实际表结构 |
|---|---|
| 表名 `intention_node` | `t_intent_node` |
| `intent_name` | `name` |
| `intent_desc` | `description` |
| `level` 传字符串 `'DOMAIN'` / `'CATEGORY'` | `level` 是 `smallint`，0=DOMAIN/1=CATEGORY/2=TOPIC |
| 未提供 `id` | `id varchar(20)` NOT NULL，无默认值 |

且它建议建一个 `guli-ecommerce` 父节点 + 两个子节点，而**父节点非叶子不影响子节点被识别**——可以直接把两个 MCP 节点挂在 `chat` 下（与已验证可用的 `weather` 同构），更少改动。

## Requirements

- R1 确认 bootstrap 能启动（含最小配置清单与外部依赖）
- R2 在意图树中注册两个 MCP 节点，指向 `product_detail_query` / `product_stock_query`
- R3 节点带可用的 `description` 与 `examples`，使 LLM 意图识别能稳定区分"详情"与"库存"两类问法
- R4 使意图树缓存正确失效，新节点对识别器可见
- R5 验证识别器能命中谷粒商城节点（而非 `chat-free` 闲聊或其他节点）
- R6 端到端验证：一次真实对话提问 → 命中 MCP 工具 → 返回谷粒商城真实数据
- R7 记录实际启动的服务清单；不改动两个工具对外的 name/description/inputSchema

## Acceptance Criteria

全部通过。原始证据在 `evidence/`（`sse-detail.txt`、`sse-stock.txt`、`intent-trees.json`、`evidence-e2e.txt`）。

- [x] AC1 bootstrap 启动成功，无致命错误 —— `Started RagentApplication in 20.519 seconds`
- [x] AC2 `GET /intent-tree/trees` 能查到两个新节点，`kind=2`、`mcpToolId` 正确、`enabled=1`、无子节点
- [x] AC3 交集两侧各有独立证据：**左**（意图树）`GET /intent-tree/trees` 有这两个 `kind=2` 节点；
      **右**（注册表）启动日志有 `MCP 工具注册成功, toolId: product_detail_query|product_stock_query`
- [x] AC4 `ragent:intent:tree` 缓存在建节点后失效（走 API 自动清），新节点对识别器可见
- [x] AC5 一次真实对话请求，返回内容包含谷粒商城的**真实商品名或真实库存数字**
- [x] AC6 SSE `tool` 事件或 bootstrap 日志中能看到 MCP 工具被真实调用（toolId 与参数可见）
      —— 这是交集**确实生效**的最终证据（没挂载的工具不可能被调用）
- [x] AC7 记录最终服务清单，确认未引入非必要服务

### 验收证据表

| AC | 判据 | 实测值 | 证据位置 |
|---|---|---|---|
| AC1 | 启动完成 | `Started RagentApplication in 20.519 seconds`；`MCP Server [default] 返回 7 个工具` | `/tmp/ragent-bootstrap.log` |
| AC2 | 节点属性 | `guli-product-detail` → `kind=2 / product_detail_query / enabled=1 / children=0`；`guli-product-stock` → `kind=2 / product_stock_query / enabled=1 / children=0` | `evidence/intent-trees.json` |
| AC3左 | 意图树侧 | 同上两行 | `evidence/intent-trees.json` |
| AC3右 | 注册表侧 | `MCP 工具注册成功, toolId: product_detail_query` / `product_stock_query` | `evidence/evidence-e2e.txt` |
| AC4 | 缓存失效 | 建节点后 `EXISTS ragent:intent:tree = 0`；两次对话后 `= 1`（识别器从 DB 重建，**含新节点**） | 同上 |
| AC5 | 真实商品名/价 | `华为 HUAWEI Mate 30 Pro 星河银 8GB+256GB`、`价格：￥6299.0000`、`品牌 ID：9`、`分类 ID：225` | `evidence/sse-detail.txt` |
| AC5 | 真实库存数 | 仓库1 `120/5/115`、仓库2 `40/10/30`、汇总 `160/15/145` | `evidence/sse-stock.txt` |
| AC6 | SSE `tool` 事件 | 详情问法 → `{"name":"product_detail_query",...,"status":"start"/"end","ok":true}`；库存问法 → `{"name":"product_stock_query",...}` | 两个 sse-*.txt |
| AC6 | 服务端收到调用 | mcp-server 日志 `16:27:51 toolId=product_detail_query, skuId=1` / `16:28:09 toolId=product_stock_query, skuId=1`（与两次提问时间精确对应） | `evidence/evidence-e2e.txt` |
| AC7 | 服务清单 | 新增仅 `bootstrap` 一个 JVM（PID 87855）；既有 9214/9215/9099 与容器未增减 | 见下 |

**AC6 的闭环意义**：AC3 只证明"两侧数据都在"，AC6 证明"两侧的交集确实被装配成了 Agent 工具并被调用"——
注册表里没有的工具不可能出现在 SSE 的 `tool` 事件里，这是交集生效的不可伪造证据。

**AC7 服务清单**：本轮新增 **1 个 JVM**（`bootstrap` :9090）。未新增容器、未起网关、未起 ES/RocketMQ/LightRAG/MinerU。
运行时共存进程：`guli-product` :9214、`guli-ware` :9215、`mcp-server` :9099、`bootstrap` :9090。

### 一处未提交的配置依赖（归属未确定，如实记录）

本任务**未改任何 Java 代码**，但整条链路依赖一处**工作区里未提交**的配置改动：

```
bootstrap/src/main/resources/application.yaml:36
-  ragent.engine.type: workflow
+  ragent.engine.type: agent
```

这是 `/agent/v1/chat` 与 `GET /agent/v1/meta` 能存在的**前置条件**（`@ConditionalOnAgentEngine`）；
若回退为 `workflow`，本任务验收用的入口会 404，而 `/agent/v1/meta` 的 MCP 工具挂载路径也不复存在。

- 该改动 mtime 为 `2026-09-13 14:47:59`，**早于本任务执行**（bootstrap jar 构建于 16:25，进程启动于 16:26）。
- 父任务与本任务的 prd/implement 均**未记录**过它，**我无法确定是用户手动改的还是父任务期间改的**。
- 未经确认，我未提交也未回退它。**是否需要纳入提交由用户决定**（见 Phase 3.4）。

> **修正（执行中发现）**：AC3 原写"`mcpConfigured` 由 1 变为 3"是错的。该字段是 **boolean**
> （`AgentToolCatalog.mcpToolCount() > 0` 的结果，见 `AgentMetaController`），不是计数；
> 且基线本就为 `true`（`weather_query` 已构成交集），加节点后不会变化。
> `mcpToolCount()` 的真实值也没有任何日志或接口暴露，"挂载了几个工具"不可直接观测。
> 故改为上面的证据链：交集两侧分别取证，再由 AC6 的真实调用闭环。

## Out of Scope

- 不改动 guli2（父任务已完成其所需的唯一 1 行改动）
- 不改动 mcp-server 的工具定义与契约
- 不做 `esearch`（ES 检索）、订单、购物车等其他谷粒商城能力
- 不为此新增前端页面；用现有对话入口或直接调 API 验证
- 不修复 guli2 master 的既有缺陷（父任务已记录 3 个，均另有处置）

## Key Decisions

- **D1 用管理 API 建节点，不写 SQL**：`POST /intent-tree` 会自动清意图树缓存（R4），
  而 SQL 直插需要额外手工删 `ragent:intent:tree`，多一步易漏。且 API 会做 `intentCode` 重复校验。
- **D2 两个 MCP 节点直接挂在 `chat` 下（level 1），不建 `guli-ecommerce` 父节点**：
  与已验证可用的 `weather` 同构，改动最小；父节点只是展示层分组，对识别无影响。
- **D3 重点补齐 `description`，偏离现有"全部留空"的惯例**：
  现有 8 个节点 description/examples 全空，`weather` 靠名字自解释尚可；但"商品详情查询"与"商品库存查询"
  语义高度重叠（详情里有价格、库存查询也带商品名），而 agent 档下 `description` 就是工具描述
  （`toBinding`），留空会让 ReAct 主 Agent 在两个工具间误选。**这是本任务唯一的刻意偏离，依据是机制而非偏好。**
  `examples` 一并补上（agent 档不读，但 workflow 档的 `buildPrompt` 会读，成本为零）。
  `param_prompt_template` **不填**——agent 档是死配置，填了徒增误解。
- **D4 验收看真实数据，不看"链路连通"**：与父任务 D1 一致，AC6 必须是库里的真实商品名/库存数字。

## Technical Notes

### 入口与鉴权

- 端口 9090，context-path `/api/ragent`（`application.yaml:2-4`）
- 鉴权：`SaTokenConfig:59-80` 全拦 `/**`，仅放行 `/auth/**` 与 `/error`
- 取 token：`POST /api/ragent/auth/login`，body `{"username":"admin","password":"admin"}`
  → `LoginVO.token`；后续请求带 header `Authorization: <token>`（注意 `application.yaml:319` `token-name: Authorization`，
  **不是** `Bearer ` 前缀——与谷粒商城的约定不同，别串了）
- 对话入口二选一，**当前档位 `agent` 走后者**：
  - `GET /api/ragent/rag/v3/chat`（workflow，`RAGChatServiceImpl` 无装配条件，恒定可用）
  - `GET /api/ragent/agent/v1/chat`（agent，`@ConditionalOnAgentEngine`）
  - 两者都是 `SseEmitter` 流式，参数 `question`(必填) + `conversationId`(可选)
- 探活：`GET /api/ragent/agent/v1/meta` → `mcpConfigured`

### 顺序约束

`McpClientAutoConfiguration:71-95` 在 `@PostConstruct` 里连接 MCP server（URL 末尾非 `/mcp` 会补上）、
`initialize()`、`listTools()`，然后逐个注册。**连接失败只 `log.error` 并跳过，不阻断启动，也没有重试**。
→ mcp-server 必须先于 bootstrap 启动。谷粒商城的 guli-product/guli-ware 也必须活着，否则工具能列出但调用报错。

### 其他

- Ragent 与谷粒商城共用同一个 Redis 实例（`127.0.0.1:6379`，密码 `ruoyi123`），
  `guli:auth:*`（父任务种的）与 `ragent:*` 两套 key 互不干扰。
- mcp-server 的 9099 端口当前由父任务启动的实例占用（PID 68002）；bootstrap 作为 MCP 客户端连它即可，无需另起。
- `RocketMQ` / `ES` / `LightRAG` / `MinerU` 对对话链路均非必需（`rag.keyword.type: none`、`rag.graph.type: none`）。
- `ragent.demo-mode: false`，且它不是 mock 档；注意 `DemoModeInterceptor:49` 硬编码只拦 `/rag/v3/chat`，
  **不覆盖 `/agent/v1/chat`**。

## 核心机制：`t_intent_node` ∩ `tools/list`（已源码确认）

**两条执行路径都要求 `t_intent_node` 里有对应行，不存在"只在 MCP server 注册就能被对话调用"的路径。**

`agent` 档（当前档位）的交集逻辑，`agent/src/main/java/com/nageoffer/ai/ragent/agent/tool/AgentToolCatalog.java:126-152`：

```java
Map<String, List<IntentNode>> nodesByToolId = intentNodeRegistry.listMcpToolNodes()...  // ① kind=2 的叶子
Map<String, McpToolExecutor> executors = mcpToolRegistry.listAllExecutors()...          // ② tools/list 发现的
nodesByToolId.forEach((toolId, nodes) -> {
    McpToolExecutor executor = executors.get(toolId);
    if (executor == null) { unavailableToolIds.add(toolId); return; }   // ①有②无 → 只 warn，不挂载
    bindings.add(toBinding(toolId, nodes, executor));                   // 交集 → 挂成 ReAct 工具
});
```

两个方向都是硬的：配置了但注册表没有 → 进 `unavailableToolIds` 只打 warn；注册表有但没配置 → 不进候选。
谷粒两个工具当前属于后者（`tools/list` 有、`t_intent_node` 无）。

**agent 档下真正生效的字段**（`toBinding`，`:154-161`）：

| 节点字段 | agent 档作用 |
|---|---|
| `name` | → 工具 displayName |
| `description` | → 工具 description，**ReAct 主 Agent 据此决定调不调** |
| `param_prompt_template` | ❌ **死配置**，只有 workflow 档读 |
| `prompt_template` | ❌ 不参与工具描述 |
| `examples` | ❌ agent 档不读；workflow 档的 `buildPrompt` 会读 |

**探活口径**：`mcpToolCount()` = `resolveMcpToolBindings(new ArrayList<>()).size()`，即交集大小，
经 `GET /agent/v1/meta` 暴露。**但暴露出来的 `mcpConfigured` 是 boolean（`mcpToolCount() > 0`），不是计数**，
故它无法反映"挂载了几个工具"，见上方 AC3 修正。最终改用 SSE `tool` 事件闭环。

## 已知产品限制（影响验收问法）

两个工具的 `inputSchema` 只有 `skuId`（integer，必填）。用户问"华为 Mate 30 Pro 多少钱"时问题里
没有 skuId，ReAct Agent 无从填起 → 这类纯商品名问法**不在本任务可达范围内**。
本任务验收用带 id 的问法（如"SKU 1 的商品详情"）。按名检索需要另做一个检索工具，属新范围。

## Open Questions

无阻塞项。Q1（agent 档是否走意图树）与 Q2（参数从哪来）均已由源码确认，见上两节。
