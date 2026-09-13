# 执行计划：Ragent 对话 → 谷粒商城 MCP 工具

```
S0 前置校验（依赖探活 + mcp-server 先起）
 └ S1 起 bootstrap(:9090)
    └ S2 登录取 token
       └ S3 基线探活 GET /agent/v1/meta   ← 仅确认服务可用（口径已修正，见下）
          └ S4 POST /intent-tree × 2 建节点
             └ S5 校验意图树 + 缓存已失效
                └ S6 复探 GET /agent/v1/meta   ← 仅确认服务可用（口径已修正，见下）
                   └ S7 端到端对话验证
                      └ S8 收尾取证与服务业清单
```

> **⚠️ 计划修正（执行中发现）**：原计划以 `mcpConfigured` 的 "1 → 3" 作为核心证据，**该口径不成立**。
> `mcpConfigured` 是 **boolean**（`AgentToolCatalog.mcpToolCount() > 0`），基线本就 `true`，
> 且 `mcpToolCount()` 的真实值无任何日志/接口暴露，"挂载了几个工具"不可直接观测。
> **改为三方证据链**：S5 取证交集左侧（意图树）、bootstrap 启动日志取证交集右侧（注册表）、
> **S7 的 SSE `tool` 事件**闭环——注册表里没有的工具不可能出现在 `tool` 事件里。
> S3/S6 因此降级为"确认服务可用"，不再是验收依据。

---

## S0 前置校验

- [x] 确认硬依赖（缺任一 bootstrap 起不来）

| 依赖 | 检查 |
|---|---|
| PostgreSQL + pgvector | `docker exec ragent-pg psql -U postgres -d ragent -c "SELECT extname FROM pg_extension;"` 期望含 `vector` |
| Redis | `redis-cli -a ruoyi123 ping` 期望 PONG（缺了 `SnowflakeIdInitializer` 会硬抛，启动即失败） |
| S3 :9000 | `curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/` 期望 403（S3 无签名访问的正常响应） |
| `BAILIAN_API_KEY` | 环境变量已设 |
| **mcp-server :9099** | `ss -ltnp \| grep 9099` —— **必须先起**，`McpClientAutoConfiguration` 连接失败只 log 不重试 |
| guli-product :9214 / guli-ware :9215 | `ss -ltnp \| grep -E "9214\|9215"` |

- [x] 确认 9090 未被占用

## S1 启动 bootstrap

- [x] 启动（后台，日志落 `/tmp/ragent-bootstrap.log`）
  ```bash
  cd /home/lyz/code/Ragent
  nohup java -jar bootstrap/target/*.jar > /tmp/ragent-bootstrap.log 2>&1 &
  ```
  > 若 jar 不存在或过期，先 `mvn -pl bootstrap -am package -DskipTests -Dmaven.test.skip=true`。
  > 坑：管道后接 `echo $?` 取到的是 `tail` 的退出码，必须用 `${PIPESTATUS[0]}` 或核对 jar 时间戳。

- [x] 等待并判定（用 `until` 循环，超时给足——bootstrap 装配比微服务重）
  ```bash
  until grep -qE "Started RagentApplication|APPLICATION FAILED TO START|Error starting" /tmp/ragent-bootstrap.log; do sleep 3; done
  tail -60 /tmp/ragent-bootstrap.log
  ```
- [x] 关键日志检查：MCP 客户端是否连上，有无
      `意图树配置的 MCP 工具当前不可用` / MCP 连接 error
- [ ] **回滚点**：`kill <pid>`

## S2 登录取 token

- [x] 登录（**无鉴权放行路径**，见 `SaTokenConfig:59-80`）
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:9090/api/ragent/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
  echo "token_len=${#TOKEN}"
  ```
- [x] 注意：后续请求头是 `Authorization: <token>`，**不加 `Bearer ` 前缀**
      （`application.yaml:319` `token-name: Authorization`；与谷粒商城的约定不同，别串）

## S3 基线探活

- [x] 记录基线
  ```bash
  curl -s -H "Authorization: $TOKEN" http://localhost:9090/api/ragent/agent/v1/meta
  ```
  实测 `mcpConfigured=true`（boolean）。原计划的"期望 1"已被证伪，本步降级为服务可用性确认。

## S4 建两个意图节点

- [x] 商品详情节点
  ```bash
  curl -s -X POST http://localhost:9090/api/ragent/intent-tree \
    -H "Authorization: $TOKEN" -H "Content-Type: application/json" -d @- <<'JSON'
  { "intentCode":"guli-product-detail", "name":"商品详情查询", "level":1, "parentCode":"chat",
    "kind":2, "mcpToolId":"product_detail_query", "enabled":1, "sortOrder":10,
    "description":"…", "examples":["…"] }
  JSON
  ```
- [x] 商品库存节点
  ```bash
  curl -s -X POST http://localhost:9090/api/ragent/intent-tree \
    -H "Authorization: $TOKEN" -H "Content-Type: application/json" -d @- <<'JSON'
  { "intentCode":"guli-product-stock", "name":"商品库存查询", "level":1, "parentCode":"chat",
    "kind":2, "mcpToolId":"product_stock_query", "enabled":1, "sortOrder":11,
    "description":"…", "examples":["…"] }
  JSON
  ```

  `description` 的写法要求（agent 档下它就是工具描述）：
  - 详情节点：强调"商品名称/标题/价格/图片/销售属性/规格参数"，强调**需要 SKU ID**
  - 库存节点：强调"各仓库库存数量/锁定库存/可用库存/是否缺货"，强调**需要 SKU ID**
  - 两者都要点明"SKU ID 是数字"，让 ReAct Agent 知道要从问题里找数字
  - 互相划清界限：详情 = 商品是什么；库存 = 还有多少货

- [ ] **回滚点**：`DELETE /api/ragent/intent-tree/guli-product-detail`（库存同理）

## S5 校验意图树与缓存

- [x] 树里能查到两个节点
  ```bash
  curl -s -H "Authorization: $TOKEN" http://localhost:9090/api/ragent/intent-tree/trees \
    | python3 -m json.tool | grep -E "guli|kind|mcpToolId|children"
  ```
  断言：`kind=2`、`mcpToolId` 精确等于 `product_detail_query` / `product_stock_query`、`enabled=1`、**无子节点**
- [x] 确认 Redis 缓存已失效（API 内部会清）
  ```bash
  docker exec guli-redis redis-cli -a ruoyi123 --no-auth-warning EXISTS ragent:intent:tree
  ```
  期望 `0`（已清，下次识别会从 DB 重建）。**这是 S4 走 API 而非 SQL 的原因。**

## S6 复探

- [x] 再探一次
  ```bash
  curl -s -H "Authorization: $TOKEN" http://localhost:9090/api/ragent/agent/v1/meta
  ```
  实测仍为 `mcpConfigured=true`（与基线一致，符合预期——它是 boolean）。本步降级为服务可用性确认。
  交集是否建成由 S5（树侧）+ 启动日志（注册表侧）+ S7（真实调用）三方闭环认定。

## S7 端到端对话验证（AC5/AC6）

- [x] 提问（SSE 流式，`curl -N` 关缓冲；验收用**带 skuId 的问法**，原因见 prd「已知产品限制」）
  ```bash
  curl -N -s -H "Authorization: $TOKEN" \
    "http://localhost:9090/api/ragent/agent/v1/chat?question=请查一下%20SKU%201%20的商品详情" \
    | tee /tmp/agent-chat-detail.txt
  ```
- [x] 断言（**必须逐条核对，不能只看最终答复通顺**）
  - SSE 里出现 `tool` 事件，且 toolId 为 `product_detail_query`
  - 最终答复含**真实商品名**「华为 HUAWEI Mate 30 Pro 星河银 8GB+256GB」或真实价格 `6299`
    —— 这是"数据来自 MySQL 而非模型编造"的判据
  - 交叉核对 `/tmp/ragent-bootstrap.log` 里 mcp-server 侧是否收到调用
- [x] 库存工具同理（**换一个问法**，验证 ReAct 选对了工具而非撞对的）
  ```bash
  curl -N -s -H "Authorization: $TOKEN" \
    "http://localhost:9090/api/ragent/agent/v1/chat?question=SKU%201%20现在还有货吗" \
    | tee /tmp/agent-chat-stock.txt
  ```
  断言：走 `product_stock_query`，答复含真实库存数（120/40 或汇总 145）
- [x] 若 Agent 不使用工具、直接凭常识回答 → 是 `description` 引导不足，
      调整措辞后重试（见 design.md 未决风险）

## S8 收尾取证

- [x] 保存两份 SSE 原始输出与关键日志片段到本任务目录
- [x] 验收证据表记入 prd.md（三方证据链，非 `mcpConfigured` 前后对照）
- [x] 记录最终服务清单（AC7）：新增的只有 bootstrap 一个 JVM，未引入非必要服务
- [ ] 清理：`kill` bootstrap；**容器与 guli/mcp-server 是否停由用户决定**
- [ ] 更新 spec（Phase 3.3）与提交（Phase 3.4）

---

## 危险点与回滚点汇总

| 位置 | 风险 | 回滚 |
|---|---|---|
| S1 起 bootstrap | 依赖缺失导致启动失败 | 看日志定位；不改配置硬闯，缺什么补什么或回报 |
| S4 建节点 | `intentCode` 撞名 / `level` 传错类型 | API 有查重与校验，失败即返回；已建节点可 DELETE |
| S4 建节点 | description 写偏 → Agent 误选工具 | 改 description 即可，`PUT /intent-tree/{id}` |
| 副作用 | 新工具出现在所有对话的候选里 | `enabled=0` 停用，或 DELETE |
| 全程 | 不改任何代码，故无代码回滚需求 | —— |
