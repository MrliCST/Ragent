# 执行计划：谷粒代理令牌

```
S0 前置：服务在跑 + 生成密钥对 + 导出 scope 白名单
 └ S1 R1 Ragent：密钥配置 + 签发器
    └ S2 R2 Ragent：上下文载体 + 客户端注入 + 工具侧写入
       └ S3 R2 验证【关键】：令牌到达 mcp-server，且并发不串号
          └ S4 R3 guli：验签器 + 绑定解析 + 拦截器双分支
             └ S5 R4：种绑定测试数据
                └ S6 端到端：AC1 同会员 / AC2 换会员
                   └ S7 安全：AC4 篡改过期 / AC5 越权路径 / AC6 停用会员
                      └ S8 回归：AC3 公共数据 / AC8 真人登录
                         └ S9 收尾：spec + 提交
```

**S3 是整个计划的分水岭**：在谷粒侧一行未改时就证明"令牌真的到了、且不串号"。这样后面任何失败都唯一地指向谷粒侧校验，不会与传输问题混淆。

两侧开关默认**关闭**，分两步上线：先上 guli 侧（关闭态，零行为变化），再上 Ragent 侧并打开。

---

## S0 前置确认

- [ ] 四个服务仍在监听（用户此前决定全部保留运行）
  ```bash
  ss -ltnp | grep -E ":(9090|9099|9214|9215)"
  ```
  期望 4 行。缺谁按 `09-13-ragent-chat-to-guli-e2e/implement.md` 的 S0/S1 补起。

- [ ] 取 ragent token（**不带 `Bearer ` 前缀**，`application.yaml:319` `token-name: Authorization`）
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:9090/api/ragent/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin"}' \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
  echo "token_len=${#TOKEN}"
  ```

- [ ] **建第二个 ragent 用户**（S3 的并发串号验证需要两个不同 userId，只有 admin 验不了）
  > 目的：并发发起两条对话，核对各条收到的令牌 `sub` 是否与发起用户对应。
  > 若注册入口不便，退而求其次：在 `McpToolBridge` 上加一个并发单测直接断言。**但必须有一步真正验过**，不能默认成立。

- [ ] 生成 RSA 密钥对（**不落仓库**，放工作目录外）
  ```bash
  mkdir -p ~/.ragent-delegation && cd ~/.ragent-delegation
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out delegation-private.pem
  openssl rsa -in delegation-private.pem -pubout -out delegation-public.pem
  openssl pkey -in delegation-private.pem -pubout -outform DER | sha256sum   # 记录 kid 用
  ```
  约定 `kid = ragent-2026-09`（首次）。确认工作目录的 `.gitignore` 已排除这两个文件。

- [ ] **导出 scope 白名单**（AC5 的判据来源，**从实际 Controller 映射导出，不凭印象写**）
  ```bash
  cd /home/lyz/code/guli2/ruoyi-cloud-guli-mail
  grep -rn "Mapping(" --include=*.java guli-mall/*/src/main/java/com/atlearn/guli/controller/ \
    | sed 's/.*Mapping(//' | sort -u
  ```
  据此把路径分成「C 端可代理」与「后台禁止」两类，结果记入本文件的「执行记录」。
  起点判断：C 端为 `CartController` / `OrderController` / `PmsDisplayController`；后台为 `PmsSpuController` / `PmsBrandController` / `PmsAttrController` / `PmsAttrGroupController` / `PmsCategoryController` / `Wms*Controller`。
  > **默认拒绝**：不在白名单内的一律 403，不写黑名单。

- [ ] **回滚点**：本步无副作用（仅本地文件）。

---

## S1 R1：密钥配置 + 签发器

- [ ] `bootstrap/src/main/resources/application.yaml` 加 `agent.delegation` 段：
      `enabled: false`（默认关）、`private-key-path`、`key-id`、`issuer: ragent`、`audience: guli-mall`、`ttl-seconds: 60`
- [ ] 新增 `agent/.../delegation/DelegationTokenIssuer.java`：用 hutool `cn.hutool.jwt` 签 RS256
      —— **先核对 hutool 5.8.37 的 RS256 API 形态**（`JWTSignerUtil.rs256(Key)` 的签名/验签用法），
      确认后再写。签不出正确令牌是最容易白跑的一步。
- [ ] 私钥读取失败时的行为：`enabled=false` 或缺私钥 → **不签发**，`McpToolBridge` 回落到服务 token 路径（不阻断公共数据查询）
- [ ] **回滚点**：`enabled: false` 即回到现状。

## S2 R2：上下文载体 + 客户端注入 + 工具侧写入

- [ ] 新增 `rag/.../core/mcp/DelegationContext.java`：`KEY` / `HEADER` 常量 + ThreadLocal + `snapshot()`
      （无令牌时返回 `McpTransportContext.EMPTY`，注意 `create(Map)` 不接受 null 值）
- [ ] `McpClientAutoConfiguration.java:72-75`：加 `httpRequestCustomizer` 与 `transportContextProvider`
- [ ] `McpToolBridge.java:100-110`：`executor.execute(...)` 前后 `DelegationContext.set/clear`（用 `finally`）
- [ ] `mcp-server`：`McpServerConfig.java:50` 配 `contextExtractor` 从 `HttpServletRequest` 取 header 填 `McpTransportContext`
- [ ] `GuliApiSupport.headers(properties, delegationToken)` 加重载：**代理令牌优先于服务 token**
- [ ] 各 `*McpExecutor` 从 `exchange.transportContext().get(KEY)` 取令牌并传入（`GuliProductDetailMcpExecutor.java:65-109` 等）
- [ ] 打包并重启 bootstrap + mcp-server
  ```bash
  cd /home/lyz/code/Ragent
  mvn -pl bootstrap -am package -DskipTests -Dmaven.test.skip=true
  ```
  > 坑（沿用前任务记录）：管道后接 `echo $?` 取到的是 `tail` 的退出码，**核对 jar 时间戳**更可靠。

- [ ] **回滚点**：`agent.delegation.enabled: false` → 不写上下文、不加 header，mcp-server 回落服务 token。

## S3 R2 验证【关键，谷粒侧一行未改】

- [ ] 让 mcp-server 在收到 header 时临时打印 `sub` / `jti`（**临时日志，S9 前移除**）
- [ ] 单条验证：发一条会走 MCP 的问句
  ```bash
  curl -N -s -H "Authorization: $TOKEN" \
    "http://localhost:9090/api/ragent/agent/v1/chat?question=%E8%AF%B7%E6%9F%A5%E4%B8%80%E4%B8%8B%20SKU%201%20%E7%9A%84%E5%95%86%E5%93%81%E8%AF%A6%E6%83%85" \
    | tee /tmp/delegation-probe.txt
  ```
  断言：mcp-server 日志出现令牌；`sub` 等于发起用户。
  > **修正（实测）**：本条原写「谷粒调用仍成功（走的是服务 token —— 说明回落正确）」，
  > 与 `design.md` 的「代理令牌存在时**优先于**服务 token，**二者不混用**」自相矛盾。
  > 正确的预期是：**只要带了代理令牌，谷粒就必然 401**（谷粒侧未改，Redis 里没有 `guli:auth:<jwt>`）。
  > 实测正是如此（`code=10011 未登录`），这是**对的**——若此时反而成功，说明令牌被静默丢弃、回落到了服务账号，
  > 那才是本任务要消灭的串号行为。AC3 的「公共数据不退化」验的是**不带**代理令牌时的回落，不是这里。

- [ ] **并发串号验证（本任务最高风险项，不可跳过）**
  用两个账号**同时**发起对话，核对 mcp-server 收到的两条令牌的 `sub` 各自对应，没有互换。
  > 依据：`McpToolBridge` 用 ThreadLocal 把令牌桥接给 SDK 的 `transportContextProvider`，
  > 而该 supplier 的求值线程取决于 SDK 内部实现（`McpSyncClient.withProvidedContext`）。
  > 这依赖的是 SDK 的**内部**行为而非公开契约，必须实测，不能靠推理。
  > 若发现串号 → 立即停，改用"每次调用新建 client"或直接改 SDK 用法，**不要带着这个风险往下走**。

- [ ] **回滚点**：本步未改谷粒；Ragent 侧关开关即恢复。

## S4 R3：guli 侧验签器 + 绑定解析 + 拦截器双分支

- [ ] `guli-common/pom.xml` 加 `cn.hutool:hutool-jwt`（版本由 `hutool-bom` 管，不写版本号）
- [ ] 新增 `guli-common/.../agent/AgentJwtVerifier.java`：
      校验签名（`kid` 找公钥）、`iss`、`aud`、`exp`（±30s），**任何失败返回 `null`，不抛异常、不放行**
- [ ] 新增 `guli-common/.../agent/AgentTokenClaims.java`（`ragentUserId` / `jti` / `scope` / `session`）
- [ ] 新增 `guli-common/.../agent/AgentScopePolicy.java`：**默认拒绝**的路径白名单（清单来自 S0）
- [ ] 新增 `guli-common/.../agent/AgentBindingResolver.java`：接口 + 本次的 Redis 实现
      （键 `guli:agent-binding:<ragentUserId>` → `memberId`），**解析不到返回 `null`**，含短 TTL 本地缓存
- [ ] `UserInfoInterceptor.java:19-34` 改为双分支（见 `design.md` 代码块）
      —— **分支二代码保持与改造前逐字一致**，这是 AC8 不回归的保证
- [ ] 配置：加 `guli.agent-token.enabled: false`（**默认关**）+ 公钥 PEM（`kid → PEM` 映射结构，为将来换 JWKS 留位）
- [ ] 重新构建并重启 guli-product（:9214，AC1/AC2 只用到它）
- [ ] 断言：`enabled=false` 时行为与改造前完全一致（真人登录仍通、服务 token 仍通）
- [ ] 打开 `enabled=true`，重启

- [ ] **回滚点**：`guli.agent-token.enabled=false` → 分支一整体跳过；或 `git checkout` 拦截器。

## S5 R4：种绑定测试数据

- [ ] 取得 `mcp_test` 的 memberId（F14 的既有会员）
  ```bash
  docker exec guli-mysql mysql -uroot -p... -N -e \
    "SELECT id FROM \`ry-cloud\`.ums_member WHERE username='mcp_test' AND status=1"
  ```
  > `status=1` 是必须条件（谷粒语义：1=正常）。AC6 要验证 `status != 1` 的会员解析为 `null`。

- [ ] 写入绑定 —— **键与值的实际形态见 S6 的契约表**（裸 ragentUserId + `{"memberId","active"}`）。
      直接跑 `verify-ac.py bind` 即可，不要手敲（手敲容易漏掉 `active` 而白排查半天）。

- [ ] **回滚点**：`DEL` 该键即解绑。

## S6 端到端（AC1 / AC2）

> 已封装为 `verify-ac.py`（本任务目录下），`bind` → `ac1 ac2` 即可；`unbind` 是回滚点。
> 该脚本同时校验两处证据：**模型答案的菜名**与 **mcp-server 收到的令牌 `sub`**。
> 只有两处都对，才算身份真的生效——只看答案可能被"碰巧答对"骗过。

- [ ] **绑定键契约以 S4 实现为准（与 design.md 有出入，见下）**

  | 项 | 值 |
  |---|---|
  | key | `guli:agent-binding:<裸 ragentUserId>`（`sub` 去掉 `ragent:` 前缀） |
  | value | `{"memberId":N,"active":<bool>}`，仅 `active=true` 且 `memberId` 非空才放行 |

  ```bash
  docker exec guli-redis redis-cli -a ruoyi123 --no-auth-warning \
    SET "guli:agent-binding:2001523723396308993" '{"memberId":1,"active":true}'
  ```

  > ⚠️ **design.md 漂移**：design.md「R4 — 绑定关系」写的是值 = 裸 `<memberId>`，
  > 但 S4 的实现把它扩成了带 `active` 的对象。这个扩展是**必要的**，不是随意发挥：
  > 裸 memberId 无法区分「未绑定」与「已绑定但会员被停用」，而 AC6 恰好要求后者解析为 `null`。
  > 接口注释（`AgentBindingResolver`）已把这条写成实现约定。
  > S9 更新 spec 时必须把 design.md 这一处改过来，否则 spec 与代码不一致。

  > ⚠️ 绑定解析在谷粒侧有 **10s 本地缓存（含负缓存）**：种完绑定要等 10s 再测，
  > 否则读到的是「未绑定」的旧负缓存，表现为莫名其妙的 401。`verify-ac.py` 已内置等待。

- [ ] AC1：以已绑定的账号发问
  ```bash
  python3 .trellis/tasks/09-13-agent-delegation-token/verify-ac.py ac1
  ```
  断言：返回的是 `mcp_test`（member 1）**自己的**购物车数据 —— 含 `Mate 30 Pro`，不含 `iPhone 11`。
  对照基线：直接以 `mcp_test` 登录谷粒调 `/cart` 的结果应一致。

- [ ] **AC2（区分"链路通了"与"串号了"的唯一有效判据）**：换一个会员，把绑定键改指向**另一个** memberId（或另建一条绑定给第二个 ragent 账号），同样提问，**数据必须随之变化**。
  > 若两次结果相同，说明身份根本没生效（多半是回落到了服务 token）。这一步不过，AC1 通过也没有意义。

- [ ] **回滚点**：删绑定键。

## S7 安全验证（AC4 / AC5 / AC6）

> 已封装为 `verify-guli-boundary.py`（AC4 / AC5 / AC8，直连 guli）。
> **断言的是 body 里的 `code`，不是 HTTP 状态**——原因见「关键发现 3」。
> `code` 期望值：未登录 `10011`、越权 `10026`。返回 `99999` 一律视为**失败**（拦截器有 bug）。

- [ ] **先跑基线**：合法令牌打 `/cart` → `code=0`。基线不过，后面所有"拒绝"都不算数。
- [ ] AC4-a 篡改签名 → `10011`
- [ ] AC4-b 过期 `exp` → `10011`
- [ ] AC4-c `aud` 不符（改成 `other-system`）→ `10011`；`iss` 不符 → `10011`
- [ ] AC4-d `sub` 缺 `ragent:` 前缀 → `10011`
- [ ] AC4-e `alg=none` 降级攻击 → `10011`
- [ ] AC4-f 未知 `kid` → `10011`
- [ ] AC4-g 完全无 `Authorization` 头 → `10011`
- [ ] AC4-h 合法签名但 `scope` 为空 → `10026`（纵深防御：无授权范围直接拒）
- [ ] AC4-i **降级专项（读代码，不只看测试）**：确认分支一 `verify()` 返回 null 后
      **继续走分支二**、且分支二在 Redis 里查不到该 JWT 因而仍 401。
      即「验签失败」绝不能等价于「放行」。当前实现的落点：
      `UserInfoInterceptor.preHandle`（`claims == null` 直接落到第 84 行的 Redis 分支）
- [ ] AC5：持代理令牌访问后台接口 → `10026`
      （用例已含 `/maintain/brands`、`/category/listTree`；另加两条边界：
      精确路径不得退化成前缀匹配 → `/cartXYZ` 必须拒；
      目录式前缀后必须有内容 → `/display/item/` 本身必须拒）
- [ ] AC6：把绑定指向 `status != 1` 的会员 → `10011`（用 `verify-ac.py ac6`）

- [ ] **回滚点**：无（只读验证）。

## S8 回归（AC3 / AC8）

- [ ] AC3：公共数据不退化——`/display/item/{skuId}` 走服务 token 仍可查
  ```bash
  curl -s "http://localhost:9214/display/item/1" -H "Authorization: Bearer mcp-live-test-token-0001"
  ```
- [ ] AC8：真人登录不退化——用 `mcp_test` 走谷粒 `/login` 拿 token，调 `/cart` 仍通（**分支二逐字未改**）
- [ ] 回归上一任务的链路：`product_detail_query` 仍返回真实商品数据（`grep "product_detail_query" /tmp/mcp-server.log | tail -3`）

## S9 收尾

- [x] 移除 S3 的临时日志
- [x] 更新 spec：本次新增两条项目约定——「代理令牌经 MCP 传输层传递，禁止走工具参数」与「谷粒侧校验必须双分支且失败不降级」，
      按 `trellis-update-spec` 落盘并挂索引
- [x] 把 S0 的 scope 白名单清单、S3 的并发验证结论与日志、S6 的两次对照输出存入本任务目录
- [x] 提交（Phase 3.4）。**跨两个仓库**：Ragent 与 guli2 各自提交。
      排除用户个人笔记 `文档.md` 与 `.claude/settings.local.json`（沿用上一任务的处理）。
      **确认私钥 PEM 未被提交**（S0 已放工作目录外，提交前再核一次）

---

## 危险点与回滚点汇总

| 位置 | 风险 | 回滚 |
|---|---|---|
| S2 ThreadLocal 桥接 | SDK 内部线程模型导致**令牌串号** | S3 强制验证；不通过则改用每次调用新建 client |
| S3 并发验证 | 跳过不验 = 把最严重的风险带进生产 | 不可跳过；这是本任务唯一依赖 SDK **内部**行为的点 |
| S4 拦截器双分支 | 写成"验签失败即放行" → 任意人可冒充 | 默认拒绝；AC4-d 逐条核对代码路径 |
| S4 分支二改写 | 动了真人登录路径 → AC8 回归 | 分支二保持逐字一致；出问题 `git checkout` |
| S4 scope 白名单 | 写成黑名单 → 后台接口可被触达 | 白名单从实际 Controller 导出（S0）；默认拒绝 |
| S1 私钥 | 误提交进仓库 | PEM 放工作目录外 + `.gitignore`；S9 提交前再核 |
| S4/S2 契约 | 两侧开关未按序上线 → 谷粒收到不认识的头 | 均默认关；先上 guli 侧关闭态，再上 Ragent 侧 |
| 全程 | 副作用仅限：新增配置项、新增请求头、拦截器多一条分支 | 无数据迁移，无破坏性变更；两侧开关独立可关 |

---

## 执行记录

### S0（2026-09-13）

**服务状态**：`:9090` ragent、`:9099` mcp-server、`:9214` guli-product、`:9215` guli-ware 在跑。
`guli-shopcart` **未启动**（jar 已构建 `guli-mall/guli-shopcart/target/guli-shopcart.jar`，端口 `9216`）。

**ragent 账号**

| username | id | 口令 | 用途 |
|---|---|---|---|
| admin | `2001523723396308993` | `admin` | 对照 |
| probe_user | `2001523723396308994` | `probe123456` | S3 并发串号验证 |

- `t_user.id VARCHAR(20)`；登录直接把 DB 的 id 作 sa-token loginId（`AuthServiceImpl.java:54-55`），故 `UserContext.getUserId()` 即上表 id。
- ragent 口令是**明文比对**（`AuthServiceImpl.java:76-81` `stored.equals(input)`），故直接插行即可。
- 库访问方式：`docker exec ragent-pg psql -U postgres -d ragent`（**本机没有 psql**）。
- 实测 `probe_user` 登录成功，`/user/me` 返回 `userId=2001523723396308994`。

**谷粒会员**

- 现仅 `mcp_test` 一个，`memberId=1`，`status=1`。
- 访问方式：`docker exec guli-mysql mysql -uroot -ppassword`（口令见 `config/local-config/nacos/datasource.yml:7`）。
- 谷粒口令是 **BCrypt**（`$2a$10$...`），与 ragent 的明文不同。
- `gulimall:cart:*` **为空**；`guli:agent-binding:*` 为空（尚未种绑定，符合预期）。

**scope 白名单（从实际 Controller 映射导出，非凭印象）**

C 端可代理：

| 服务 | base | 路径 |
|---|---|---|
| guli-shopcart | `/` | `/cart`、`/addCart/{skuId}`、`/updateForCart`、`/deleteCart/{skuId}` |
| guli-order | `/` | `/order/confirm`、`/order/cancel`、`/order/submit` |
| guli-product | `/display` | `/display/item/{spuId}`、`/display/esearch` |

后台禁止：

| 服务 | 路径 |
|---|---|
| guli-product | `/maintain/**`（PmsSpuController）、`/attribute/**`、`/brand/**`、`/category/**` |
| guli-ware | `/purchase/**`、`/purchaseDetail/**`、`/wareInfo/**`、`/wareSku/**` |

> 附带确认：`PmsDisplayController` 的路径变量名是 **`spuId`** 而非 `skuId`，而 MCP executor 以 skuId 语义调用它（`GuliProductDetailMcpExecutor.java:101`）。既有事实，非本次引入。

**Nacos `dev` 命名空间只有 13 个配置**，其中谷粒相关**仅 `guli-product.yml`**：没有 `guli-shopcart.yml`。
启动 shopcart 会撞上与上一任务 `guli-ware` 相同的 `can not find primary datasource`，需用外部配置文件兜底（已知模式，见 `09-13-guli-mcp-live/implement.md:139-157`）。

### ⚠️ S0 发现的计划缺陷：AC1 / AC2 的前提不成立

**mcp-server 暴露的谷粒工具只有两个，都是公共数据、都没有用户维度**：

| TOOL_ID | 调用 | 性质 |
|---|---|---|
| `product_detail_query` | `GET guli-product /display/item/{skuId}` | 公共数据 |
| `product_stock_query` | guli-ware 库存 | 公共数据 |

**没有任何购物车（或其它用户维度）工具。** 核实依据：

- `mcp-server/.../executor/` 下全部 8 个 executor，谷粒相关仅上述两个；
- 全仓 grep `cart` 只命中本次新建的 `DelegationProperties.java:71`（`scope = "cart:read"`）；
- `git log -S cart -- mcp-server/ agent/` 无任何提交；
- 上一任务 `09-13-ragent-chat-to-guli-e2e/prd.md:143` 明确写了「不做 esearch、订单、**购物车**等其他谷粒商城能力」。

因此 `prd.md` 的 AC1（"在对话里问「我的购物车有什么」，返回该会员自己的购物车数据"）与 AC2（换会员看数据变化）
**按原文无法执行**——那条链路从未存在，不是本次改造能接通的对象。`prd.md` 的 Goal 里"用户在对话里问
「我的购物车有什么」，拿到的是自己的数据"这句承诺，同样落在当前工具集之外。

这不影响 R1–R5 的技术设计本身，也不影响 AC3/AC4/AC5/AC6/AC8：
这几个 AC 打的是 `/display/item/1` 或后台路径，**都不需要用户维度接口**。

> **注意**：`/cart` 这个**谷粒接口**是存在的（`CartController.java:67` `@GetMapping("/cart")`），
> 缺的只是 ragent 侧通往它的 **MCP 工具**。所以补起来是"加一个 executor"，不是"谷粒新增接口"。

### 范围增补（用户决策）：补购物车 MCP 工具

三个选项（补工具闭环 / 只验到谷粒边界 / AC1-AC2 挂起另开任务）中，用户选定**补购物车 MCP 工具**，
以保住 AC1/AC2 的端到端验收语义。因此本任务新增一项交付：

- `mcp-server` 新增 `GuliCartMcpExecutor`（`TOOL_ID = cart_query` → guli-shopcart `GET /cart`）
- `GuliMcpProperties` 新增 `shopcart` 段；`mcp-server/application.yml` 加 `guli.shopcart.base-url: http://localhost:9216`
- **意图树需新增一个节点**（见下）

### 关键发现：工具靠**意图树**挂载，注册了不等于可用

`AgentToolCatalog.resolveMcpToolBindings()`（`agent/.../tool/AgentToolCatalog.java:127-140`）是
`intentNodeRegistry.listMcpToolNodes()` 与 `mcpToolRegistry.listAllExecutors()` 求**交集**：
只注册 executor 而不配意图节点，工具永远不会挂到模型上（会进 `unavailableToolIds` 并被 warn）。

现有 `mcp_tool_id` 非空的节点共 3 个，都在 `parent_code='chat'`、`level=1`、`kind=2`：

| intent_code | name | mcp_tool_id |
|---|---|---|
| `weather` | 天气查询 | `weather_query` |
| `guli-product-detail` | 商品详情查询 | `product_detail_query` |
| `guli-product-stock` | 商品库存查询 | `product_stock_query` |

且模型看到的工具描述取自**意图节点的 `description`**（`toBinding` 用 `IntentNode::getDescription` 拼接），
不是 executor 里 `Tool.builder().description(...)` 的那份。两处都要写好。
`examples` 字段供分类器做嵌入匹配，决定用户问句能否路由到该节点。

### 关键发现 2：意图树有 Redis 缓存，直插 SQL 会被旧缓存盖住

**症状**：`seed-intent-cart.sql` 已执行、DB 里节点存在、mcp-server 也返回了 `cart_query`，
bootstrap 启动日志却打出 `MCP 工具注册成功, toolId: cart_query`（这是
`DefaultMcpToolRegistry` 注册 **executor**，与意图树无关），而模型侧
`Toolkit` 只注册了 `search_knowledge / flush_memory / product_detail_query /
product_stock_query / weather_query` —— **没有 `cart_query`**，且**一条 `不可用` warn 都没有**。

「有 warn 说明意图树配了但没 executor；一条 warn 都没有，说明意图树里根本没这个节点。」
这个区分是定位的关键：两种失败模式长得像，日志能一眼分开。

**根因**：`DefaultIntentClassifier.loadIntentTreeData()`（`rag/.../core/intent/DefaultIntentClassifier.java:70-84`）
**先读 Redis**，只有缓存为空时才回落数据库并回写：

```java
List<IntentNode> roots = intentTreeCacheManager.getIntentTreeFromCache();  // key: ragent:intent:tree, TTL 7 天
if (CollUtil.isEmpty(roots)) { roots = loadIntentTreeFromDB(); ... }
```

我直接 `psql INSERT` 绕过了后台 CRUD 的 `clearIntentTreeCache()`，于是 7 天 TTL 的旧树一直生效。
（另注：bootstrap **重启不清这个缓存**，缓存不随进程生命周期走。）

**解法**（等价于后台增删改节点的行为）：

```bash
docker exec guli-redis redis-cli -a ruoyi123 --no-auth-warning DEL ragent:intent:tree
```

（ragent 用的就是 guli 的这个 Redis，`bootstrap/src/main/resources/application.yaml:30-33`，
库是 6379/`ruoyi123`；没有独立的 `ragent-redis` 容器。）

删除后下一次请求即从 DB 重建（日志：`意图树缓存不存在，需要从数据库加载` → `已保存到Redis缓存, 根节点数: 2`），
`cart_query` 立刻挂上并被真实调用：

```
Registered tool 'cart_query' in group 'ungrouped'
MCP 远程工具调用完成, toolId=cart_query, params={}, contentSize=1, elapsed=442ms
```

> 本任务后续若再改意图节点（含 S5 之外的调整），**必须一并清这个 key**，否则改动不生效且没有任何报错。

### 关键发现 3：谷粒的「401 / 403」是 HTTP 200 + 业务码，不是 HTTP 状态码

`GuliExceptionHandler` 是 `@RestControllerAdvice`，`BusinessException` 走 `R.fail(code, msg)`，
**HTTP 状态码恒为 200**。"未登录 / 越权"体现在响应体的 `code`：

| code | 含义 | 本任务里的角色 |
|---|---|---|
| `10011` | `UNAUTHORIZED` 未登录 | AC4 全部用例、AC6 的期望值 |
| `10026` | `FORBIDDEN` 无权访问 | AC5 越权、AC4-h 空 scope 的期望值 |
| `99999` | `UNKNOWN_ERROR` 兜底 | **不是"被拒"**，是拦截器抛了未预期异常的信号 |

所以 AC 断言必须看 body 里的 `code`，不能看 HTTP 状态（`curl -f`、`%{http_code}` 判 401 会全部误判）。
design.md / implement.md 口语写的 "401/403" 指的都是这个业务码语义。

> 连带注意：兜底的 `99999` 会把拦截器里的 NPE 之类伪装成"请求失败了"。AC4 各用例若返回 99999
> 而非 10011，**不要当成通过**——那说明拦截器有 bug。

### 已建立的验证脚手架（三个脚本都在本任务目录）

| 脚本 | 覆盖 | 打法 |
|---|---|---|
| `verify-ac.py` | AC1 / AC2 / AC6 | 走完整链路（登录 ragent → 对话 → MCP → 谷粒） |
| `verify-guli-boundary.py` | AC3 / AC4 / AC5 / AC8 | 直接打 guli，绕过 ragent 与 mcp-server |
| `verify-s3-concurrency.py` | S3 并发串号 | 两个用户并发发起，核对两条令牌的 `sub` 各自对应 |

总账：`verify-ac.py` **3/3**、`verify-guli-boundary.py` **20/20**、
`verify-s3-concurrency.py` 6 轮 12 条令牌全部对应。AC7 为日志证据（见 `evidence-s6-ac1-ac2.txt`）。

> AC3 原先是手敲 `curl` 临时验的，S9 时补进了 `verify-guli-boundary.py` ——
> 它的框架（直连 guli、断言 `body.code`）本来就与 AC3 同类，补进去后八个 AC
> 的证据都落在**同一份构建、同一次运行**里，不必再靠"我记得当时是通的"。

直连的理由：AC4/AC5 测的是**谷粒侧验签器与拦截器本身**，走 ragent 会把 mcp-server 的包装层
和模型话术夹在中间，失败无法归因。直连时"请求 = 谷粒收到什么，响应 = 谷粒回了什么"，判据干净。

> ⚠️ **干跑结果不可当结论**：在谷粒仍是旧代码（`enabled=false` + 未重启）时跑
> `verify-guli-boundary.py ac4`，会看到一堆 `code=10011` 的 "OK"。
> 那些**什么也没证明**——它们只是旧拦截器在 Redis 里找不到登录态而已，
> 与验签器是否正确地拒绝了篡改令牌毫无关系。只有**基线用例（合法令牌 → `code=0`）先通过**，
> 其余用例的"拒绝"才有意义：否则分不清是"安全拦住了"还是"链路根本没通"。
> 脚本已把基线放在第一条就是为此。

### 关键发现 4：谷粒配置里内嵌的公钥抄错一个字符（已交 S4 修）

`guli-shopcart` 与 `guli-product` 的 `application.yml` 中 `guli.agent-token.keys.ragent-2026-09`
内嵌的公钥，与 `~/.ragent-delegation/delegation-public.pem` 在 **base64 第 138 位**（0-based）不一致：
配置是 `73VlesoOS`，实际是 `73XlesoOS`。

**为什么这个错字特别危险**：它不会让解析失败。base64 解出来仍是结构合法的 DER，
`KeyFactory` 会成功构造出一个"合法但错误"的 RSA 公钥，`loadPublicKeys()` 照常打印
「代理令牌公钥加载完成」，然后**每一次验签都落 `签名不匹配` → 返回 null → 全部 401**。
排查方向会被引到"令牌签发有问题"，而真凶是配置抄错。

实测确认（用 `delegation-private.pem` 签一个 RS256 令牌，分别用两份公钥验）：

```
实际公钥(正确): 验签通过
配置里的公钥: 验签失败 -> InvalidSignatureError: Signature verification failed
```

> 教训：这种"看起来能加载但其实是错的"，必须拿**真实密钥对做一次端到端验签**才能发现，
> 光看启动日志"加载成功"是会骗人的。

### 关键发现 5：两个谷粒服务都是 Dubbo provider，**启动顺序**会决定成败

`guli-product` 与 `guli-shopcart` 都导出 Dubbo 服务（`RemoteProductService` /
`RemoteShopCartService`），都去抢默认的 **20880**。谁先起谁拿到，后起的那个直接启动失败：

```
Failed to bind NettyServer on /0.0.0.0:20880, cause: 地址已在使用
```

**必须先起 guli-product（:9214），等它把 20880 占住，再起 guli-shopcart**。
反过来起（本次实际踩到）会得到上面那条 `BindException`——报错说的是 Dubbo 端口，
与"购物车服务"看起来毫无关系，容易误判成配置问题。

```bash
# 正确顺序
java -jar target/guli-product.jar --spring.main.lazy-initialization=true &     # 先
# 等 9214 监听 + 20880 归属 guli-product
java -jar guli-mall/guli-shopcart/target/guli-shopcart.jar --spring.config.additional-location=file:/tmp/guli-shopcart-datasource.yml &
```

> 顺带：`guli-ware`（已在跑）占着 **20881**。所以停服务时只停自己要停的，
> 别用 `pkill -f guli` 之类的宽匹配把 ware 一起带走。

### 关键发现 6：谷粒的**成功**码是 200，与 HTTP 状态码同为 200 —— 一个会吞掉所有失败的断言陷阱

`R.SUCCESS = 200`、`R.FAIL = 500`（`ruoyi-common-core/.../domain/R.java:25`）。结合「关键发现 3」
（所有业务失败也是 HTTP 200），得到：

| | HTTP 状态 | body.code |
|---|---|---|
| 成功 | 200 | **200** |
| 未登录 | 200 | 10011 |
| 越权 | 200 | 10026 |

于是 `if http_status == 200: 通过` 这种写法**永远为真**——每一次拒绝都会被判成通过。
本任务的两个脚本一律断言 `body.code`，且 `SUCCESS = 200` 不是 0，就是为避开这一点。
（我第一版按 `SUCCESS = 0` 写，AC8 因此误报失败；实测值与 `R.java:25` 对照后才改正——
**"测试自己错了"与"被测对象错了"必须分得清**，这次是前者。）

### 关键发现 7：模型调用会**挂死在一条半死的 TCP 连接上**（环境问题，非本任务缺陷）

**症状**：AC1/AC2 跑到一半卡住，**180 秒无任何 SSE 增量**，最终 bootstrap 日志报：

```
ModelException: Model request timeout after PT5M [model=qwen-max, provider=openai]
```

进程没死、端口在听、没有异常栈——只是**再也不产出 token**。表现极像"代理令牌链路把模型调用
搞坏了"，但两者无关。

**排查过程（每一步都排除了一个假设）**：

| 假设 | 验证 | 结论 |
|---|---|---|
| DashScope 不可达 | `curl` 连通耗时 0.14s | 排除 |
| 非流式接口坏 | 非流式 completion 0.38s 正常 | 排除 |
| 流式接口坏 | 流式请求 0.92s 正常返回 | 排除 |
| 代码/令牌问题 | 重启 bootstrap 后**同一份代码**即恢复正常 | 排除 |

**真因**：`ss -tnp` 看到 JVM 到 `39.96.198.249:443` 的一条 ESTAB 连接
**Send-Q = 44140 字节**——发出去的数据堆在发送队列里没人 ACK，连接已被中间设备黑洞掉，
而 HttpClient 连接池仍在**复用它**。`curl -4` 走新建连接正常，`curl -6` 失败，进一步指向路径问题。

**处理**：重启进程即可丢弃连接池里的死连接（本次即如此解决）。

**为什么它值得记下来**：这正是兄弟任务 `09-13-agent-stream-failure-hardening` 的射程——
「run 已经开着、模型不再产 token」的失败形态，当前代码没有任何超时兜底，
**并且**因此暴露了第二个问题：

> 挂死的 run 从未走到 finalizer，`AgentRunGate` 的 Redis 键
> （`ragent:agent:running:<userId>`，TTL = `sseTimeoutMs × 2` = 30 分钟）
> **整整占满 30 分钟**才自动释放。这期间该用户任何新对话都被
> `ClientException("当前会话处理中，请稍后再发起新的对话")` 顶回来——
> 用户看到的是"我什么都没干，它就说我在处理中"。
> 本次靠 `redis-cli DEL ragent:agent:running:2001523723396308993`（与 `...994`）手工清掉。

**边界**：这不是代理令牌引入的缺陷——改造前同样会发生。本任务不改它（属于另一个任务的 R 范围），
但**验证时必须能识别它**，否则会把环境抖动误判成自己改坏了。

### 构建注记：`-DskipTests` 仍会**编译**测试，缺类时打包失败

`mvn package -DskipTests` 会在 `mcp-server` 上失败：

```
YouComSearchMcpExecutorTest.java:[218,13] 找不到符号 类 YouComSearchMcpExecutor
...
repackage failed: Unable to find main class
```

`-DskipTests` 跳过的是测试**执行**，不是测试**编译**；一个既有测试引用了已被删掉的类，
于是编译失败、repackage 拿不到主类，报出的却是"找不到主类"这种指向完全错误的信息。

绕过：`mvn -o package -Dmaven.test.skip=true`（`maven.test.skip` 才连编译一起跳过）。
本次 mcp-server 由此得到 27918305 字节的 fat jar 并成功重启。

### AC8 回归结论（开关关 + 新代码）：通过

在 `enabled=false`（yml 默认）+ 刚构建的新代码下重跑真人登录路径：

```
[OK] AC8 真人 cart-seed-m1→member1 /cart: code=200, msg=操作成功
[OK] AC8 真人 cart-seed-m2→member2 /cart: code=200, msg=操作成功
[OK] AC8 无令牌仍 401: code=10011
```

**这一步的意义**：它隔离出"改造本身是否有副作用"。分支二在开关关闭时逐字未变，
两个真人会员各自查到自己购物车、无令牌仍 10011 —— 说明拦截器的双分支改造
（构造器注入、token 提取上移）**没有破坏存量行为**。这比直接在开关打开时测 AC8 更严格地
排除了"分支一干扰分支二"的可能。

> 顺序说明：先跑"开关关"，再跑"开关开"。开关打开时 AC8 仍要复测一次
> （那时真人 token 会先经过 `verify()` 返回 null 再落分支二，多走一段代码）。

### 阻断修复：guli2 两个既有缺陷（非本任务设计范围，但 AC1/AC2 的前置）

`addCart` 这条路径**从未被任何任务跑通过**，因此积累了多个潜伏缺陷。为种出 AC1/AC2 所需的购物车数据，
必须依次修掉；两处都是明显笔误，改动极小、无行为风险。

**缺陷 1 — MyBatis-Plus lambda 写法错误**（`guli-product/.../dubbo/RemoteProductServiceImpl.java:38`）

```java
// 改前：x -> x.getSkuId() 是 lambda 表达式，编译器生成的合成方法名
//       （lambda$getSkuSaleAttrValueListBySkuId$1e1bc17$1）不是真实 getter，
//       PropertyNamer.methodToProperty 解析不了 → 查询直接抛异常
Wrappers.<PmsSkuSaleAttrValue>lambdaQuery().eq(x -> x.getSkuId(), skuId)
// 改后：方法引用
Wrappers.<PmsSkuSaleAttrValue>lambdaQuery().eq(PmsSkuSaleAttrValue::getSkuId, skuId)
```

**缺陷 2 — VO 漏实现 `Serializable`**（`guli-api/.../domain/vo/RmeSkuSaleAttrValueVo.java:15`）

该类**已经 `import java.io.Serial` 并声明了 `serialVersionUID`**，却漏了 `implements Serializable`。
Dubbo 默认严格序列化检查直接拒绝：
`[Serialization Security] ... has not implement Serializable interface`。
同包的 `RmeSkuInfoVo` / `RmeCartItemVo` 都正确实现了，仅此一个漏。
**注意**：`guli-api` 是共享契约，改它需同时重建 provider（guli-product）与 consumer（guli-shopcart）。

> 缺陷 1 未修时表现为 MyBatis 异常；修掉后才暴露出缺陷 2——两者是串行阻塞的，不是并列的。

### 测试数据（AC1/AC2/AC6 的判据来源）

`ums_member` 现有三个会员（口令哈希沿用 `mcp_test` 的 BCrypt 值）：

| id | username | status | 用途 |
|---|---|---|---|
| 1 | `mcp_test` | 1 | AC1 基准会员 |
| 2 | `mcp_probe2` | 1 | AC2 换会员 |
| 3 | `mcp_disabled` | 0 | AC6 停用会员 |

购物车已种入，且**两份内容明显不同**——这是 AC2「换会员数据必须变化」能判的前提
（若都为空，串号与正常在单会员场景下结果相同，AC2 会失去判据）：

| 会员 | 购物车内容 |
|---|---|
| memberId=1 | SKU 1 · 华为 Mate 30 Pro 星河银 8GB+256GB · ¥6299 |
| memberId=2 | SKU 9 · iPhone 11 黑色 128GB · ¥5999 |

种法：用 `seed-auth-token.sh` 给两个会员各种一个 Redis 登录态（`cart-seed-m1` / `cart-seed-m2`），
再调真实 `POST /addCart/{skuId}` 走完整业务路径（而非手写 Redis 值）。

### 服务启动方式（重启时必须原样带上参数）

| 服务 | 端口 | 启动命令要点 |
|---|---|---|
| ragent bootstrap | 9090 | 从仓库根 `java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar`；S3 需加 `AGENT_DELEGATION_ENABLED=true` 与 `AGENT_DELEGATION_PRIVATE_KEY_PATH` 两个环境变量 |
| mcp-server | 9099 | 从 `mcp-server/` 目录 `java -jar target/mcp-server-0.0.1-SNAPSHOT.jar` |
| guli-product | 9214 | **必须带 `--spring.main.lazy-initialization=true`**，否则 `PmsSpuServiceImpl` 因 `BaseEsMapper` 缺失启动失败（上一任务已记录，本次又踩了一次） |
| guli-shopcart | 9216 | `--spring.config.additional-location=file:/tmp/guli-shopcart-datasource.yml`（Nacos 无 `guli-shopcart.yml`，复用 ware 的兜底配置） |

> 坑（本次实际踩到）：`kill $PID` 后**不能只看 `kill -0` 的循环结果就下结论**——
> 循环跑满次数也会正常退出，打印出来的「已停」是假的。必须用 `ss -ltnp` 核对端口真的释放。
> 同一坑还有：`cmd | tail; echo $?` 取到的是 `tail` 的退出码，构建失败会被读成成功（本次也踩了一次）。
---

### S9（2026-09-13）收尾记录

**临时日志已清**：`AgentDelegation.logProbe()` 与 `McpServerConfig` 里的探针调用一并删除
（含其 `claim()` / `pad()` 辅助与两个正则常量），mcp-server 重新打包
（`-Dmaven.test.skip=true`，27918305 字节）并重启。清完后**重跑了全部 AC**，
确认拿掉探针不影响链路：`verify-ac.py` 3/3、`verify-guli-boundary.py` 20/20。

**AC 证据集中到一份构建上**：AC3 原先只有手敲 curl 的临时结果，S9 时补进
`verify-guli-boundary.py`（本就同类的"直连 guli + 断言 body.code"框架），
现在八个 AC 的证据都出自同一次运行，不再依赖"我记得当时是通的"。

**spec 落盘**：新建 `.trellis/spec/backend/agent-delegation-token.md`
（含 7 段强制结构 + 两条项目约定），挂入 `backend/index.md`；
并在 `guides/cross-layer-thinking-guide.md` 增补一条边界契约与一段说明
——**该指南原有的「新 context holder 必须注册 TTL」规则与 `DelegationContext`
刻意用裸 ThreadLocal 恰好相反**，不写清楚下次会有人"按指南把它修坏"。

**提交**（跨两个仓库，各自提交）：

| 仓库 | 分支 | 提交 | 文件数 |
|---|---|---|---|
| Ragent | `feature/guli-mcp-live` | `1c68790` | 33 |
| guli2 | `feature/agent-delegation-token`（从 `master` 新建） | `b9dd49b` | 14 |

- **私钥未进仓**：提交前扫过两边的暂存内容（`grep "BEGIN.*PRIVATE KEY"`），均为 0；
  仓库内无 `*.pem`。另在 `.gitignore` 补了 `delegation-private.pem` / `*-private.pem`
  兜底规则（正本在 `~/.ragent-delegation/`，本就在工作目录之外），并用
  `git check-ignore -v` 验证规则确实生效。公钥可入库——谷粒配置里内嵌的就是它。
- **有意排除**：`文档.md`（用户个人笔记）、`.claude/settings.local.json`、
  `09-13-agent-stream-failure-hardening/`（尚未获批的任务目录）、
  以及上一任务遗留的 `docker-compose-infra.yml`、`gulimall_pms.sql`
  和两处任务文档的改动（与本次交付无关，混进来只会让提交说不清）。
- **guli2 为何开分支**：该仓库既往直接在 `master` 上提交，但按惯例改动默认分支前先开分支。
  如欲并入：`git checkout master && git merge --ff-only feature/agent-delegation-token`。

**遗留待决（不阻塞，需用户拍板）**：两处 `enabled` 的**提交默认值是 `false`**
（`design.md` 的"分两步上线/可一键回滚"就是这么设计的），而本次 AC1–AC8 全部是在
`--guli.agent-token.enabled=true` 的**启动参数覆盖**下验的。即"验过的状态"与
"提交的默认状态"不同。要么保持默认关闭（安全，符合设计），要么改成默认打开
（则需在打开态重跑一遍 AC 再提交）。**本次未擅自改动默认值。**
