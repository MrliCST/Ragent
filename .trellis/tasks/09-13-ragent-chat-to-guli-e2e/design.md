# 设计：Ragent 对话 → 谷粒商城 MCP 工具

需求与验收见 `prd.md`；机制与源码证据见 `prd.md` 的「核心机制」一节，此处不重复。
本文件只写**怎么做**与**为什么这么做**。

## 架构与边界

本次不改任何 Java 代码。链路已经具备，缺的是**数据配置**与**运行时装配**：

```
用户提问
  → AgentChatController  GET /api/ragent/agent/v1/chat
      → ReAct 主 Agent（qwen-max / bailian）
          → Toolkit 中的原生工具
              ↑ 由 AgentToolCatalog.resolveMcpToolBindings() 装配
              ↑ = t_intent_node(kind=2 的叶子) ∩ tools/list
                  → McpToolBridge → McpClientToolExecutor.callTool(name, args)
                      → mcp-server :9099  POST /mcp
                          → GuliProduct*McpExecutor
                              → guli-product :9214 / guli-ware :9215
                                  → MySQL ry-cloud
```

**改动面**：`t_intent_node` 新增 2 行（经 `POST /intent-tree`）+ 清 `ragent:intent:tree` 缓存。
零代码改动。

## 契约

### 建节点请求（`IntentNodeCreateRequest`）

两个节点，除 `intentCode` / `name` / `description` / `examples` / `mcpToolId` / `sortOrder` 外全部一致：

```json
{
  "intentCode": "guli-product-detail",
  "name": "商品详情查询",
  "level": 1,
  "parentCode": "chat",
  "kind": 2,
  "mcpToolId": "product_detail_query",
  "description": "……",
  "examples": ["……"],
  "enabled": 1,
  "sortOrder": 10
}
```

约束（源码决定，不是偏好）：

| 约束 | 原因 |
|---|---|
| `kind=2` | `NodeScoreFilters` / `listMcpToolNodes` 按 `isMCP()` 过滤 |
| 必须是叶子（无子节点） | `listMcpToolNodes()` 只在 `leafNodes` 上过滤 |
| `mcpToolId` 必须与 `tools/list` 的 tool name **完全相等** | 交集按字符串键匹配；`resolveMcpToolBindings` 会对节点侧 `trim()` |
| `level=1` + `parentCode=chat` | 与已验证可用的 `weather` 同构；`level` 是 `Integer`（0=DOMAIN/1=CATEGORY/2=TOPIC），**不能传字符串** |
| `paramPromptTemplate` 不填 | agent 档不读，填了是死配置 |
| `intentCode` 全局唯一 | `IntentTreeServiceImpl:125-127` 建前查重 |

`description` 是本次的核心工作产物——agent 档下它就是工具描述，直接决定 ReAct 主 Agent 选哪个工具。

### 探活口径

`GET /api/ragent/agent/v1/meta` → `mcpConfigured` == `AgentToolCatalog.mcpToolCount()`
== `resolveMcpToolBindings(...).size()` == 交集大小。

基线 1（仅 `weather_query`），建成后 3。**这是"交集真的建起来"最硬的证据**，比看日志可靠。

## 关键权衡

**为什么走 HTTP 管理接口而不是 SQL**：`IntentTreeServiceImpl` 每次增删改都调
`clearIntentTreeCache()`（`:174` 等）。直插 SQL 必须自己删 `ragent:intent:tree`，
漏了就出现"库里明明有、识别器看不见"的幽灵问题——而 `loadIntentTreeData()`
仅在缓存为空时才回落 DB，这个坑很隐蔽。走 API 让缓存失效成为框架职责。

**为什么不建 `guli-ecommerce` 父节点**：父节点非叶子，不影响两个子节点被收集；
它只是展示层分组。`MCP_INTEGRATION_GUIDE.md` 的草稿建了三层，属不必要的复杂度。

**为什么 `description` 必须认真写**：两个工具的语义高度重叠——详情工具有价格，
库存工具也返回商品名；"商品 1 的信息"这类问法两边都能接。留空 description 会让
ReAct 主 Agent 在两者间随机漂移。这是与现有 8 个节点"description 全空"惯例的
**刻意偏离**，依据是 `toBinding` 的代码事实。

## 兼容性与回滚

- 新增节点不影响既有 8 个节点；`enabled=0` 即可停用，`DELETE /intent-tree/{id}` 可移除
- 回滚点：`DELETE /api/ragent/intent-tree/guli-product-detail` 与 `.../guli-product-stock`；
  或直接 `DELETE FROM t_intent_node WHERE intent_code LIKE 'guli-product-%';` 后清 Redis key
- 不动 mcp-server、不动 guli2、不动 bootstrap 配置，故父任务的成果不受影响
- 副作用边界：意图树是全局的，新增工具会出现在**所有**对话的工具候选里（不只商品问题）。
  description 写得越准，越不容易被无关问题误触发

## 未决风险

| 风险 | 应对 |
|---|---|
| bootstrap 启动失败（S3/SnowflakeId/LLM provider 校验） | 逐个看日志定位；S3 已探活（403 是正常响应） |
| ReAct Agent 不用工具、直接凭常识回答 | 若发生，是 description 引导不足 → 调整 description 措辞重试 |
| MCP 工具调用报错但 Agent 静默兜底 | 同时看 bootstrap 日志与 SSE `tool` 事件，不只看最终答复 |
| 内部 LLM 调用可能超时（意图/参数/应答多次调用） | SSE 超时 `agent.sse-timeout-ms=900000`，足够 |
