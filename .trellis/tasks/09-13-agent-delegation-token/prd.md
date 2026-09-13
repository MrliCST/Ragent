# 谷粒代理令牌：ragent 以用户身份调用谷粒微服务

## Goal

让 ragent 在调用谷粒商城的接口时能够以**终端用户的身份**进行，而不是当前的单一服务账号。

用户价值：用户在对话里问「我的购物车有什么」「我那个订单到哪了」，拿到的是**自己**的数据；谷粒侧能审计到"这次操作是哪个 ragent 用户在哪次会话里发起的"。

当前状态做不到这件事：MCP 适配层用配置里的一个固定服务 token 调谷粒，查出来的是**那个服务账号**的数据。公共数据（商品详情、库存）因此能跑通，一旦碰用户维度就是串号。

## Background

以下为已确认事实，证据均带 `file:line` 锚点，实现时若存疑回到锚点复核。

### ragent 侧

| # | 事实 | 证据 |
|---|---|---|
| F1 | 用户身份是 `String userId`，来自 sa-token | `framework/.../context/UserContext.java:58`；`AgentChatServiceImpl.java:73` |
| F2 | MCP 工具调用不携带任何用户身份 | `agent/.../tool/McpToolBridge.java:95` `callAsync(ToolCallParam)` 只看工具参数 |
| F3 | mcp-server 用**单一服务 token** 调谷粒 | `mcp-server/.../executor/GuliApiSupport.java` `headers()` → `properties.getAuth().getToken()`；值来自 `mcp-server/src/main/resources/application.yml:21` `mcp-live-test-token-0001` |
| F4 | ragent **没有** JWT / 密钥设施 | 全仓无 `jjwt` / `RS256` / `KeyPair` / `PrivateKey` 引用 |
| F5 | ragent 与谷粒**无任何账号绑定关系** | 全仓 grep `memberId` / 绑定 相关无业务命中 |
| F6 | 每次 agent run 已构造 `RuntimeContext`，工具可读 | `AgentChatServiceImpl.java:134` `agent.streamEvents(question, RuntimeContext.builder()...)`；读取的既有范式见 `KnowledgeSearchTool.java:106` `param.getRuntimeContext()` |
| F7 | ragent 用 PostgreSQL，升级走手工 SQL 目录约定 | `resources/database/schema_pg.sql`；`resources/database/upgrades/<version>/<yymmdd>_<name>.sql` |

### 谷粒侧

| # | 事实 | 证据 |
|---|---|---|
| F8 | 用户身份来自 **ThreadLocal**，接口签名里没有 userId | `guli-common/.../interceptor/UserInfoInterceptor.java`：`Authorization: Bearer <token>` → Redis `guli:auth:<token>` → `UserInfoContext.set()`；消费处 `OrderServiceImpl.java:97,153,428,598`、`CartServiceImpl.java:60,92,127,143` |
| F9 | `UserInfoInterceptor` 是**全站唯一**注入点 | `GuliWebMvcConfig.java:15-17` `addPathPatterns("/**")`；免登录白名单**仅** `/login` `/register` `/sendCode` |
| F10 | 已有内部接口层 `guli-api`（Dubbo `Remote*Service`），但**大多无用户维度** | `RemoteProductService` / `RemoteWareService` 纯参数查询（公共数据）；`RemoteShopCartService.getCartItemList()` **无参**，实现读 ThreadLocal；`RemoteOrderService.getOrderStatusBySn(orderSn)` **无归属校验**；`RemoteMemberService.getReceiveAddressList(Long memberId)` 是唯一显式传 memberId 的 |
| F11 | Dubbo **不传身份** | `ruoyi-common-dubbo/.../filter/DubboRequestFilter.java` 是纯日志过滤器 |
| F12 | 有可用的密码 / 邮箱验证码登录，会员表有唯一标识与状态位 | `AuthController` `/login`（`PasswordLoginServiceImpl` 为 `@Primary`）、`/sendCode`、`/register`；`UmsMember` 字段：`id` / `username` / `password` / `mobile` / `email` / `status`（1=正常） |
| F13 | 谷粒用 MySQL，手工 SQL | `config/business-sql-config/gulimall_ums.sql` |
| F14 | 测试基线身份已存在 | `.trellis/tasks/09-13-guli-mcp-live/seed-ums-member.sql` → `ums_member.username='mcp_test'`，口令 `mcp123456`，`status=1` |

### 由事实推出的关键约束

- **F9 决定了改造面极小**：改 `UserInfoInterceptor` 一处，`UserInfoContext.getUserId()` 在全部消费处继续工作，`Remote*Service` 签名一个都不用动。
- **F8 + F11 决定了身份只能从"请求边界"注入**，不能指望谷粒内部跨服务自动传递。
- **F7 + F13 决定了绑定关系的权威数据宜留谷粒侧**（两端不同库，跨库不便；且"谁是谁的会员"是谷粒的领域知识）。
- **F6 决定了 ragent 侧有一个现成的、框架自带 per-request 状态袋**（`RuntimeContext.put/get`），不需要引入 ThreadLocal 或 Reactor Context——ReAct 循环是异步的，ThreadLocal 会在换线程时丢。

## Requirements

### R1 — ragent 能签发短效代理令牌

ragent 在调用谷粒前，签发一个**声明"我是 ragent 用户 X"**的短效令牌。

- 令牌必须**短效**，有效期贴近单次工具调用预算（实测 MCP 调用为秒级，`product_detail_query elapsed=32ms`），而非"一个会话时长"。
- 签发密钥的选型必须使**谷粒侧不具备签发能力**——谷粒有十余个业务模块，任何一处被攻破都不应能凭空造出合法代理令牌。
- 密钥轮换不需停机。

### R2 — 令牌经 MCP 传至谷粒

令牌从 ragent 的工具调用处一路传到谷粒的请求头。

- **令牌不得出现在 MCP 工具参数里。** 工具参数是模型生成的 JSON：模型会看到它，prompt injection 可以诱导模型改写它，它不是可信信道。必须走 MCP 传输层（HTTP header）。
- ragent 侧需要一条"当前 run 的令牌 → 工具调用"的传递路径。F6 已证明该路径存在且是框架自带能力。
- mcp-server 需把令牌转发为谷粒可识别的请求头，且**不得**与自身的服务 token 混淆。

### R3 — 谷粒侧统一校验并还原用户身份

谷粒在一个统一位置校验令牌并还原出用户，使**现有全部以用户为维度的接口无需改动即可工作**。

- 改造应落在 F9 识别出的唯一注入点。
- **失败必须严格**：令牌无效时**不得**降级为匿名或其它身份通过；校验失败一律 401。这是此类改造最典型的漏洞（"验签失败就当匿名"）。
- 必须校验签发方与受众，防止甲系统的令牌被拿来调谷粒。
- 代理令牌的可访问范围应收窄：不得触达后台管理类接口（如 `PmsSpuController` 那批），仅限 C 端接口。
- 校验不得依赖谷粒侧的有状态存储（现有 Redis 查询路径保留给真人登录，不被代理令牌复用）。

### R4 — 身份映射（绑定），权威在谷粒侧

ragent 必须能把"ragent 用户"对应到"谷粒会员"。

- **已定：映射的权威方在谷粒侧。** ragent 的令牌只声明"我是 ragent 用户 X"，**不携带 memberId**；谷粒侧查绑定表得出 memberId。由此 ragent 被攻破时最多冒充**已完成过绑定的那些会员**，而非任意会员；"谁能访问谁的数据"的决定权留在数据所有者一侧。
- 推导出的结构约束：**绑定表落谷粒侧**（与 F7/F13 的跨库判断一致——两端不同库，且"谁是谁的会员"是谷粒的领域知识）；谷粒侧校验多一次绑定查询（可缓存，绑定关系几乎不变）。
- **绑定的建立必须是用户主动完成的一次操作**（凭据校验），不能由 ragent 按规则推导（如按用户名猜）——否则映射的来源不可审计。
- 被停用的谷粒会员（`UmsMember.status != 1`）不得通过绑定获得访问权。

### R5 — 可审计

谷粒侧日志必须能回答"这次操作是哪个 ragent 用户在哪次会话里发起的"。

- 需同时记录**代理人身份**（ragent 用户）与**会话标识**（`RuntimeContext.getSessionId()`，即 conversationId），而不只是被代理的会员。

## Acceptance Criteria

- [ ] AC1：用 `mcp_test` 身份（F14）完成绑定后，在对话里问「我的购物车有什么」，返回的是**该会员自己的**购物车数据，而非服务账号的数据。判据是与直接以 `mcp_test` 登录谷粒调 `/cart` 的结果一致。
- [ ] AC2：**换一个会员**做同样提问，返回的数据随之变化——证明身份真的在生效，而不是碰巧对上了。这是区分"链路通了"与"串号了"的唯一有效判据。
- [ ] AC3：`/display/item/{skuId}` 等公共数据接口在改造后**不退化**（F10 的既有能力不失效，走服务身份仍可查）。
- [ ] AC4：伪造 / 过期 / 篡改的令牌一律 401，且**不会**落到任何有效的 `UserInfoContext`。特别验证"验签失败后是否被降级放行"。
- [ ] AC5：代理令牌**无法**访问后台管理类接口（如 `PmsSpuController`）。
- [ ] AC6：`UmsMember.status != 1` 的会员无法通过绑定获得访问权。
- [ ] AC7：谷粒侧日志中能查到该次操作对应的 ragent 用户标识与会话标识（R5）。
- [ ] AC8：真人用谷粒 App 登录的原有路径**不退化**——现有 Redis 校验分支行为不变。

## Out of Scope

- **绑定流程的用户界面**：本次用手工种的绑定数据验证链路（AC1/AC2 依赖该数据），"用户在 ragent 里完成凭据校验以建立绑定"的完整流程留到后续任务。理由：链路与绑定是两个可能各自出错的环节，先固定住绑定这一侧，失败才能唯一指向链路；且绑定流程的 UX（收账号密码还是走邮箱验证码）取决于链路验证的结论。
- **谷粒侧缺失的接口**：`RemoteOrderService` 没有"我的订单列表"，也没有订单详情（只有 `getOrderStatusBySn(orderSn)`）。代理令牌解决身份，不解决接口缺失；新增订单查询接口是后续独立议题。
- **谷粒内部跨服务传身份**（F11）：若将来 order 调 ware 锁库存时也需身份，令牌需走 Dubbo attachment 转发。现阶段不需要。
- **SSO / 登录体系合并**：本方案刻意不要求两端统一认证域，绑定是替代路径。
- **ragent 侧的权限模型**：本次只做身份透传，不在 ragent 侧建立"这个用户能做什么"的授权模型。
- **模型成功率的提升**：属另一条线，见 `09-13-agent-stream-failure-hardening`。

## Notes

- **已定决策记录**：
  1. **映射权威在谷粒侧**（R4）——令牌不携带 memberId，谷粒查绑定表。取舍是牺牲一点校验性能（多一次可缓存的查询），换取"ragent 被攻破时无法冒充任意会员"。
  2. **交付边界为垂直切片**（见 Out of Scope）——本次只证明链路，不做绑定流程与订单接口。
  3. **不合并登录体系**——本方案刻意不要求两端统一认证域，这是它相对于 token 透传方案的主要优势（谷粒侧零接口改动）。

- **非阻塞的已知缺口**（记录备查，不在本次验收内）：
  - sa-token 的 `NotLoginException` 在过滤器层抛出，前端拿到的是 `SSE 请求失败（500）`。与本任务无关，见 `09-13-agent-stream-failure-hardening` 的 R3 设计。

---

> 本文件只记录需求、约束与验收。技术设计见 `design.md`，执行计划见 `implement.md`。