# 设计：谷粒代理令牌

需求与验收见 `prd.md`，事实证据与 `file:line` 锚点见其 Background 表，此处不重复。
本文件只写**怎么做**与**为什么这么做**。

## 改动面总览

| 需求 | 仓库 | 位置 | 性质 |
|---|---|---|---|
| R1 签发器 | Ragent | `agent/.../delegation/DelegationTokenIssuer.java`（新） | 新增 |
| R2 上下文载体 | Ragent | `rag/.../core/mcp/DelegationContext.java`（新） | 新增 |
| R2 客户端注入 | Ragent | `rag/.../core/mcp/McpClientAutoConfiguration.java:72-75` | 加两个 builder 调用 |
| R2 工具侧写入 | Ragent | `agent/.../tool/McpToolBridge.java:100-110` | 调用前后设/清上下文 |
| R2 服务端转发 | Ragent | `mcp-server/.../config/McpServerConfig.java:50` + 各 `*McpExecutor` + `GuliApiSupport` | 取上下文并转发 |
| R3 验签器 | guli2 | `guli-common/.../agent/AgentJwtVerifier.java`（新） | 新增 |
| R3 绑定解析 | guli2 | `guli-common/.../agent/AgentBindingResolver.java`（新） | 新增（接口 + 本次的 Redis 实现） |
| R3 拦截器 | guli2 | `guli-common/.../interceptor/UserInfoInterceptor.java:19-34` | **双分支改造** |
| R3 配置 | guli2 | nacos `guli-common.yml` 或各服务 application.yml | 加公钥与开关 |

不改：`guli-api` 的全部 `Remote*Service` 签名、`UserInfoContext` 及其全部消费处（order 4 处 / cart 4 处）、谷粒现有登录路径、ragent 的 `AgentChatServiceImpl`。

---

## R1 — 密钥与令牌

### 算法：非对称 RS256

| | 对称 HS256 | **非对称 RS256（选它）** |
|---|---|---|
| 谁能签发 | 双方 | **只有 ragent** |
| 密钥分发 | secret 要进谷粒十余个模块 | 公钥随便发，私钥只在 ragent |
| 泄露后果 | 谷粒任一模块泄露即可伪造任意代理令牌 | 仅影响验签 |

谷粒侧**不掌握签发能力**，这是选它的核心理由——否则谷粒内部任何一个被攻破的服务都能凭空造出合法代理令牌。

配套：JWT header 带 `kid`；验签方维护 `kid → PublicKey` 的映射（本次配置注入，将来可换 JWKS 端点，**令牌格式不变**）；时钟偏移容忍 ±30s。

### 依赖：复用两端已有的 hutool，不引新库

- ragent：`framework/pom.xml:20-21` 已有 `hutool-all 5.8.37`，`cn.hutool.jwt` 直接可用
- guli：`pom.xml:131-133` 用 `hutool-bom 5.8.43`，需在 `guli-common` 显式加 `cn.hutool:hutool-jwt`

两端同一实现，避免一边一个 JWT 库导致的签名/编码细节差异。（hutool 的 RS256 具体 API 形态在实现时核对，见 `implement.md` S1。）

### 私钥管理

- 私钥 PEM 走**文件路径 + 环境变量覆盖**（`agent.delegation.private-key-path`），**不入库、不入仓**
- 开发/测试密钥由脚本生成到工作目录外，`.gitignore` 覆盖
- 轮换：新增 `kid` + 公钥，双密钥并存期内旧令牌仍可验，过期后再撤旧公钥

### claims

```json
{
  "iss": "ragent",
  "aud": "guli-mall",
  "sub": "ragent:<ragentUserId>",
  "session": "<conversationId>",
  "scope": ["cart:read"],
  "iat": 1757...,
  "exp": 1757... + 60,
  "jti": "<uuid>"
}
```

- **`sub` 只声明 ragent 自己的用户**，不含 memberId —— 这是 R4「映射权威在谷粒侧」的直接体现。
- `session` 承载 `RuntimeContext.getSessionId()`（= conversationId），满足 R5 审计。
- `scope` 本次只需一个 `cart:read`（AC1/AC2 只验购物车），但字段结构先立起来，供 R3 的路径白名单使用。
- 不再需要 RFC 8693 的 `act` 字段：本次只有一层委托，`iss` + `sub` 已完整表达"哪个 ragent 用户在代理自己"。

### 签发时机与 TTL：每次工具调用签一次

不是每次 run 签一次。理由：一次 run 可能因模型推理持续数分钟，run 级令牌要么被迫设长 TTL（暴露窗口大），要么在 run 中途过期（难排查）。

工具调用级签发后，**令牌只在一次 MCP 往返内有效**（实测 `product_detail_query elapsed=32ms`），TTL 60s 已极宽裕。RSA 签名是亚毫秒级，每次调用签一次无性能问题。

**关键简化**：`RuntimeContext` 本身就带 `userId` 与 `sessionId`（`AgentChatServiceImpl.java:134` 已经 `.userId(userId).sessionId(conversationId)`），因此 `McpToolBridge` **直接读 `param.getRuntimeContext()` 即可**，无需新建注入路径，`AgentChatServiceImpl` 一行都不用改。

---

## R2 — 传递通道

### 全链路

```
ragent: McpToolBridge.execute(param)
   ├─ ragentUserId = param.getRuntimeContext().getUserId()
   ├─ session      = param.getRuntimeContext().getSessionId()
   ├─ jwt = issuer.issue(ragentUserId, session)          ← R1
   ├─ DelegationContext.set(jwt)                          ← ThreadLocal
   └─ executor.execute(args)
        └─ McpSyncClient.callTool(...)
             ├─ transportContextProvider → 读 ThreadLocal 构造 McpTransportContext
             └─ httpRequestCustomizer    → 写 header X-Agent-Delegation
                          ↓ HTTP
mcp-server: McpSyncServerExchange.transportContext().get(KEY)   ← 反解出 jwt
   └─ GuliApiSupport.headers(properties, jwt) → Authorization: Bearer <jwt>
                          ↓ HTTP
guli: UserInfoInterceptor → AgentJwtVerifier + AgentBindingResolver → UserInfoContext
```

### 客户端机制（已确证，非推测）

MCP SDK 1.1.2 原生支持每调用上下文与请求定制，两端都不需要自己造轮子：

- `McpClient.SyncSpec.transportContextProvider(Supplier<McpTransportContext>)` —— 每次调用求值
- `McpSyncHttpClientRequestCustomizer.customize(HttpRequest.Builder, method, URI, body, McpTransportContext)` —— 拿到当次上下文，可写 header
- `McpTransportContext.create(Map)` / `.get(key)`

`McpClientAutoConfiguration.java:72-75` 的客户端构建加两处：

```java
HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(mcpUrl)
        .httpRequestCustomizer((builder, method, uri, body, ctx) -> {
            Object token = ctx.get(DelegationContext.KEY);
            if (token != null) {
                builder.header(DelegationContext.HEADER, token.toString());
            }
        })
        .build();

McpSyncClient client = McpClient.sync(transport)
        .transportContextProvider(DelegationContext::snapshot)   // 无令牌时返回 McpTransportContext.EMPTY
        .build();
```

### 为什么必须用 ThreadLocal（以及它为什么安全）

`transportContextProvider` 是个 `Supplier`，SDK 无法把"当前是哪次调用"传给它——它只能从**调用线程**上读。所以 `McpToolBridge.execute` 调 `executor.execute(...)` 前 `set`、`finally` 里 `clear`。

安全性的依据：`McpSyncClient.callTool` 是**同步阻塞**调用，SDK 内部 `withProvidedContext(...).block()` 在**当前线程**订阅，因此 supplier 的求值线程就是 `execute()` 的线程。`set` 与求值同线程，ThreadLocal 不会串。

**这是一个对 SDK 内部实现的依赖**，不是对公开契约的依赖。因此 `implement.md` 里把它列为**必须实测验证的一条**（验证方式：并发发两条不同用户的 MCP 调用，确认 header 不串）。若将来 SDK 改成 `subscribeOn`，症状会立刻是"用户 A 的令牌出现在用户 B 的调用里"，必须能第一时间发现。

### 服务端反解（同样是 SDK 原生）

- `HttpServletStreamableServerTransportProvider.builder()` 配 `contextExtractor`，从 `HttpServletRequest` 取 `X-Agent-Delegation` 填进 `McpTransportContext`（`McpServerConfig.java:50`）
- 工具 handler 是 `BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult>`，`exchange.transportContext().get(KEY)` 即得令牌
- `GuliApiSupport.headers(properties)` 增加带令牌的重载：**代理令牌存在时优先于服务 token，二者不混用**（`GuliProductDetailMcpExecutor.java:99-109` 等调用点改传令牌）

### 红线：令牌绝不进工具参数

工具参数是**模型生成的 JSON**：模型会看到它、会在回答里复述它、prompt injection 可以诱导模型改写它。它不是可信信道。令牌只能走传输层 header——上文的机制正是为此设计的，不需要任何妥协。

---

## R3 — 谷粒侧校验

### 改造点：`UserInfoInterceptor` 双分支

F9 已证明它是全站唯一注入点（`addPathPatterns("/**")`）。改它一处，`UserInfoContext.getUserId()` 在全部消费处继续工作。

```java
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith(TOKEN_PREFIX)) {
        throw new BusinessException(ErrorCodeEnum.UNAUTHORIZED);
    }
    String token = authHeader.substring(TOKEN_PREFIX.length());

    // 分支一：代理令牌（无状态验签）
    AgentTokenClaims claims = agentJwtVerifier.verify(token);   // 任何失败都返回 null，不抛、不放行
    if (claims != null) {
        if (!AgentScopePolicy.allows(claims.scope(), request.getRequestURI())) {
            throw new BusinessException(ErrorCodeEnum.FORBIDDEN);
        }
        Long memberId = agentBindingResolver.resolve(claims.ragentUserId());
        if (memberId == null) {
            throw new BusinessException(ErrorCodeEnum.UNAUTHORIZED);   // 未绑定 / 会员被停用
        }
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(memberId);
        userInfo.setUserKey("agent:" + claims.jti());
        UserInfoContext.set(userInfo);
        return true;
    }

    // 分支二：原有 Redis 路径，行为完全不变
    UserInfo userInfo = RedisUtils.getCacheObject(AUTH_TOKEN_KEY_PREFIX + token);
    if (userInfo == null) {
        throw new BusinessException(ErrorCodeEnum.UNAUTHORIZED);
    }
    userInfo.setUserKey(token);
    UserInfoContext.set(userInfo);
    return true;
}
```

### 语义要点（AC4 的判据）

1. **验签失败必须落到分支二，且分支二仍可能 401。** 反模式是"JWT 解析失败就当匿名放行"——这是此类改造最典型的漏洞。`verify()` 对签名错、过期、`iss`/`aud` 不符、格式错一律返回 `null`，由分支二兜底。
2. **`aud` 必校验**：否则其它系统的令牌可拿来调谷粒。
3. **`exp` 必校验**，容忍 ±30s 时钟偏移。
4. **两条分支不共享实现**：现有 Redis 查询路径原样保留给真人登录，不被代理令牌复用（R3 要求校验不依赖有状态存储）。

### scope 路径白名单（AC5）

代理令牌只对 C 端接口有效，不得触达后台管理类接口（`PmsSpuController` 那批）。校验放在分支一内部，`request.getRequestURI()` 对照 scope 允许的前缀表。

**白名单的具体路径清单在实现时从实际 Controller 映射导出**，不凭印象写——见 `implement.md` S4。原则是**默认拒绝**：不在白名单内的路径，持有代理令牌也一律 403。

### 绑定解析与缓存

`AgentBindingResolver` 定义成一个接口，本次的实现从谷粒 Redis 读（见下方取舍）。命中后本地缓存（绑定关系几乎不变），带短 TTL，避免每次 MCP 调用都打一次 Redis。

**`UmsMember.status != 1` 的会员必须解析为 `null`**（AC6）。绑定值里的 `active` 就是这个判据的载体；
本次 Redis 实现由种数据时写入，将来换成 MySQL 实现时必须在查询里 join `status` 条件算出它。

缓存含**负缓存**：解析结果为 `null` 也会被缓存 10s。改绑定后必须等过期，否则会读到旧的「未绑定」
（症状是莫名其妙的未登录），这是 AC6 用例里 `sleep(TTL+2)` 的原因。

---

## R4 — 绑定关系

### 本次：接口隔离 + Redis 测试数据

**本次不建绑定系统**（已在 `prd.md` 的 Out of Scope），只种一条测试绑定用于验证链路：

```
guli:agent-binding:<ragentUserId>  →  {"memberId":<Long>,"active":<bool>}
```

**值的形态不是裸 `<memberId>`**（本节初稿写错，实现已取代，见 `AgentBindingResolver` 接口注释与
`RedisAgentBindingResolver.parse()`）：只有 `active == true` 且 `memberId` 非空才解析出会员，否则返回 `null`。
原因是 AC6 的判据——「未绑定」与「已绑定但会员被停用」必须可区分；裸 memberId 把两者压成同一种形态，
测试就无从证明停用真的被拦住了。将来换 MySQL 实现时，`active` 必须由 `UmsMember.status == 1` 计算得出，
而不是表里另存一列可被独立改写的标志位。

沿用上一任务 `seed-auth-token.sh` 的手工种数据模式，测试身份用 `mcp_test`（F14，`ums_member` 中的既有会员）。

### 为什么不用 MySQL 表 / Dubbo 服务（这是取舍，不是省略）

| 方案 | 问题 |
|---|---|
| 绑定表 + Mapper 放 `guli-common` | `guli-common` 的 pom 只有 core/redis/web/es/amqp，**没有 MyBatis 与 datasource**，塞业务表与其基建定位相悖 |
| 绑定表放 `guli-member`，经 Dubbo 暴露 | `UserInfoInterceptor` 在 `guli-common`，而 `guli-common` **没有 dubbo 依赖**；为它加依赖会波及全部服务 |
| 独立绑定服务 | 本次交付边界不需要 |

因此把"绑定从哪来"收敛到一个接口（`AgentBindingResolver`）后面，本次用最轻的 Redis 实现。后续做绑定流程时新增 MySQL 实现 + 绑定表即可，**校验代码不动**。

---

## 契约变更

**新增跨系统契约**（Ragent ↔ guli2）：

| 项 | 值 |
|---|---|
| 传输头 | `X-Agent-Delegation: <jwt>`（ragent → mcp-server） |
| 转发头 | `Authorization: Bearer <jwt>`（mcp-server → guli，与真人登录同头不同语义） |
| 令牌算法 | RS256，header 带 `kid` |
| 必需 claims | `iss` `aud` `sub` `exp` `iat` `jti`；`session` `scope` 可选 |

**对既有契约的影响**：无。真人登录路径、`Remote*Service` 签名、SSE 事件契约全部不变。这是本方案相对"改接口签名"路线的核心优势。

---

## 取舍

| 取舍 | 选择 | 理由 | 代价 |
|---|---|---|---|
| 密钥算法 | RS256 非对称 | 谷粒侧不具备签发能力 | 密钥管理比共享 secret 麻烦（私钥文件 + 公钥分发 + kid 轮换） |
| 映射权威 | 谷粒侧 | ragent 被攻破时无法冒充任意会员 | 谷粒每次校验多一次绑定查询（已用缓存缓解） |
| 令牌传递 | 传输层 header | 工具参数是模型生成内容，不可信 | 依赖 SDK 的 per-call 上下文机制；客户端需 ThreadLocal 桥接 |
| 签发粒度 | 每次工具调用 | TTL 可压到 60s，暴露窗口最小 | 每次调用一次 RSA 签名（亚毫秒，可忽略） |
| 绑定存储 | 接口 + Redis（本次） | `guli-common` 无 MyBatis/dubbo，改共享模块依赖影响面过大 | 绑定数据是测试夹具而非真实系统；后续需补持久化实现 |
| 校验落点 | 改造 `UserInfoInterceptor` | 一处改动覆盖全站，接口零改动 | 拦截器逻辑变复杂；必须严格保证失败不降级 |

---

## 风险与回滚

| 风险 | 影响 | 回滚 / 缓解 |
|---|---|---|
| **SDK 内部线程模型变化**，ThreadLocal 求值线程与 `set` 不同 | 令牌串号（最严重） | 实现时**必须**做并发串号验证（`implement.md` S3）；真出问题时改用每次调用新建 client（性能差但正确） |
| **验签失败被降级放行** | 任意人可冒充（最严重） | AC4 专项验证；代码评审盯死"分支一失败必须继续走分支二" |
| `aud`/`exp` 漏校验 | 令牌可跨系统复用 / 过期可用 | AC4 覆盖篡改与过期两类用例 |
| scope 白名单写成黑名单 | 后台接口可被代理令牌触达 | 实现时**默认拒绝**，白名单从实际 Controller 映射导出（S4） |
| 谷粒侧改动影响真人登录 | 存量功能回归 | AC8 专项验证；分支二代码保持与改造前完全一致 |
| 公钥配置错误导致全量 401 | 代理调用全挂 | 开关式启用（`guli.agent-token.enabled`），出问题可一键关闭并回落到原有行为 |

**回滚点**：两侧独立。
- guli 侧：`guli.agent-token.enabled=false` → 分支一整体跳过，行为回到改造前；或直接 `git checkout` 拦截器。
- Ragent 侧：`agent.delegation.enabled=false` → 不写上下文、不加 header，mcp-server 回落到服务 token（AC3 的公共数据能力仍在）。

两侧开关都默认关闭，**分两步上线**：先上 guli 侧（关闭态，零行为变化），再上 Ragent 侧并打开。

---

## 已知局限

- **令牌不含权限模型**（已在 Out of Scope）：`scope` 本次只做路径粗粒度控制，不做"这个用户能操作哪些资源"的细粒度授权。谷粒侧的归属校验（如订单只能查自己的）仍是各业务接口自己的责任。
- **不解决用户登出/封禁的即时生效**：60s TTL 内令牌仍有效。接受"最长 TTL 内的延迟撤销"，不做 `jti` 黑名单（否则又回到有状态校验）。
- **sa-token 的 `NotLoginException` 仍会 406**：与本任务无关，见 `09-13-agent-stream-failure-hardening` 的 R3。
- **谷粒内部跨服务不传身份**（F11）：`DubboRequestFilter` 只记日志。若将来 order 调 ware 也需身份，需另加 Dubbo attachment 转发。