# Making an MCP Tool Reachable from Conversation

> How an MCP tool registered in an MCP server becomes callable by the chat Agent — and why
> registering it in the MCP server alone is never enough.

---

## Scenario: Register a new MCP tool so chat can route to it

### 1. Scope / Trigger

Trigger this spec when **any** of the following is true:

- You added a tool to an MCP server and expect users to reach it by asking a question
- You changed an MCP tool's `name` (the tool id) on the server side
- You are debugging "the tool works via `tools/call` but the Agent never calls it"
- You changed `ragent.engine.type` (the two engines resolve tools by **different** rules)

The trap this spec prevents: a tool verified working over raw MCP (`initialize` → `tools/list` →
`tools/call`) can still be **completely unreachable** from chat. Two independent registries must
intersect, and the failure is silent — no error, no warning, the Agent simply behaves as if the tool
does not exist.

### 2. Signatures

**The intersection** (`agent/src/main/java/com/nageoffer/ai/ragent/agent/tool/AgentToolCatalog.java:125-152`):

```java
// ① left: intent-tree nodes — kind == MCP, leaf, non-blank mcpToolId
Map<String, List<IntentNode>> nodesByToolId = intentNodeRegistry.listMcpToolNodes()...

// ② right: executors discovered from the MCP server's tools/list
Map<String, McpToolExecutor> executors = mcpToolRegistry.listAllExecutors()...

nodesByToolId.forEach((toolId, nodes) -> {
    McpToolExecutor executor = executors.get(toolId);
    if (executor == null) { unavailableToolIds.add(toolId); return; }  // left-only → warn, NOT mounted
    bindings.add(toBinding(toolId, nodes, executor));                  // intersection → mounted
});
```

`listMcpToolNodes()` (`rag/.../core/intent/DefaultIntentClassifier.java:109-115`):

```java
return loadIntentTreeData().leafNodes.stream()
        .filter(IntentNode::isMCP)                                       // kind == 2
        .filter(node -> node.getMcpToolId() != null && !node.getMcpToolId().isBlank())
        .sorted(Comparator.comparing(IntentNode::getId))
        .toList();
```

**Management API** (`rag/.../controller/IntentTreeController.java:59`):

```
POST /api/ragent/intent-tree        body: IntentNodeCreateRequest
```

**Cache key**: `ragent:intent:tree` (`IntentTreeCacheManager.java:45`)

### 3. Contracts

`IntentNodeCreateRequest` — the fields that actually matter for routing:

| Field | Type | Constraint | Why |
|---|---|---|---|
| `intentCode` | string | globally unique | checked at create (`IntentTreeServiceImpl`); also the node `id` in the tree |
| `kind` | Integer | **must be `2`** (`IntentKind.MCP`) | `isMCP()` filter |
| `mcpToolId` | string | **must equal the MCP tool's `name` exactly**, including case and underscores | the intersection is a plain string-keyed map lookup |
| `level` | **Integer** | `0`=DOMAIN, `1`=CATEGORY, `2`=TOPIC | **not a string**; passing `"CATEGORY"` fails |
| `parentCode` | string | parent's `intentCode` | must leave the node a **leaf** (see below) |
| `enabled` | Integer | `1` to be visible | hard SQL filter: `DefaultIntentClassifier:296` `.eq(IntentNodeDO::getEnabled, 1)` |
| `name` | string | — | becomes the tool's `displayName` in agent mode |
| `description` | string | — | **becomes the tool's description in agent mode — this is what the ReAct agent reads to choose a tool** |
| `examples` | string[] | — | read by workflow mode's intent prompt only |
| `paramPromptTemplate` | string | leave empty | **dead config in agent mode** |

**Leaf-ness is a hard requirement.** `listMcpToolNodes()` filters `leafNodes`, so an MCP node with
children is silently excluded. Do not create an intermediate grouping node above it and assume the
children still qualify — only the leaves are collected.

### 4. Validation & Error Matrix

| Condition | Result |
|---|---|
| node exists, executor missing | `unavailableToolIds` + warn log, tool **not** mounted, chat never sees it |
| node missing, executor exists | tool is **not** a candidate at all; no log line anywhere |
| `mcpToolId` case/underscore mismatch | treated as "node missing, executor exists" — silent |
| node has children | excluded from `leafNodes` — silent |
| `enabled = 0` | excluded by the SQL filter at cache build — silent |
| `kind != 2` | excluded by `isMCP()` — silent |
| `level` passed as a string | request deserialization fails, API returns an error |

> **Warning — the intent tree is cached, and the cache wins over the DB.**
>
> `DefaultIntentClassifier.loadIntentTreeData()` reads Redis first and **only falls back to the DB
> when the cache is empty** (`:70-80`). A row inserted with raw SQL is therefore **invisible** until
> `ragent:intent:tree` is deleted — the DB will look correct while the classifier sees the old tree.
>
> Use `POST /intent-tree` instead of SQL. Every mutating method in `IntentTreeServiceImpl`
> (`:174, :263, :286, :310, :352, :401`) calls `intentTreeCacheManager.clearIntentTreeCache()`,
> so going through the API makes cache invalidation the framework's job rather than yours.

### 5. Good/Base/Bad Cases

- **Good** — create the MCP node via `POST /intent-tree` with `kind=2`, `enabled=1`, a leaf position,
  an `mcpToolId` copied verbatim from `tools/list`, and a `description` that states what the tool
  answers *and what it does not*.
- **Base** — create it, then verify by asking a real question and asserting an SSE `tool` event
  carrying the tool id. Do not stop at "the row is in the table".
- **Bad** — insert the row with SQL and skip the cache flush; or copy `mcpToolId` from a doc rather
  than from the live `tools/list`; or leave `description` empty when two tools overlap semantically
  and let the ReAct agent pick between them by name alone.

### 6. Tests Required

There is no unit test that can prove the intersection is wired for a *live* server pair. Verify in
this order, and require **all three** — the first two only prove the data exists, the third proves
the wiring:

1. **Tree side** — `GET /api/ragent/intent-tree/trees`: node present with `kind=2`, exact
   `mcpToolId`, `enabled=1`, and **zero children**.
2. **Registry side** — bootstrap log on startup:
   `MCP 工具注册成功, toolId: <your-tool-id>`.
3. **The real proof** — one chat request that must produce an SSE frame
   `event:tool` / `{"name":"<your-tool-id>","status":"start"}` followed by `status:"end"` with
   `"ok":true`. A tool that was never mounted **cannot** appear here, which is what makes this
   unforgeable.

Assertion points for step 3: the `tool` event's `name` equals the expected tool id (not merely that
*some* tool ran), and the final answer contains a value that could only come from the backing store
(a real product name, a real price, a real count) — not a plausible-looking generated one.

> **Do not use `mcpConfigured` as a count.** `GET /api/ragent/agent/v1/meta` exposes
> `mcpConfigured` as a **boolean** (`AgentToolCatalog.mcpToolCount() > 0`). It is `true` as soon as
> *any* tool intersects, so it cannot tell you whether you added one. `mcpToolCount()`'s real value
> is exposed by no endpoint and no log line. The SSE `tool` event is the observable substitute.

### 7. Wrong vs Correct

#### Wrong

```jsonc
// SQL insert straight into the table — DB looks right, classifier never sees it
// INSERT INTO t_intent_node (...) VALUES ('guli-product-detail', ..., 'PRODUCT_DETAIL_QUERY', ...);
//   ^ cache not flushed           ^ wrong level type   ^ case mismatch vs tools/list  ^ has children
```

#### Correct

```jsonc
POST /api/ragent/intent-tree
{
  "intentCode": "guli-product-detail",
  "name": "商品详情查询",
  "level": 1,                            // Integer, not "CATEGORY"
  "parentCode": "chat",                  // stays a leaf
  "kind": 2,                             // IntentKind.MCP
  "mcpToolId": "product_detail_query",   // verbatim from tools/list
  "enabled": 1,
  "sortOrder": 10,
  "description": "查询某个 SKU 的商品详情：名称、标题、价格、图片、销售属性、规格参数。需要一个数字 SKU ID。与库存查询的区别：本工具回答商品是什么，不回答还有多少货。",
  "examples": ["SKU 1 的商品详情", "帮我查一下 SKU 2 的价格和规格参数"]
}
// then: GET /intent-tree/trees (tree side) + startup log (registry side) + one real chat (the proof)
```

---

## Engine Mode Changes Which Node Fields Are Live

| Node field | `workflow` mode | `agent` mode |
|---|---|---|
| `name` | label | → tool `displayName` |
| `description` | goes into the intent-classification prompt | → tool `description`; **the ReAct agent's basis for choosing** |
| `examples` | read by `buildPrompt` | not read |
| `paramPromptTemplate` | read | **dead config** |

`ragent.engine.type` also gates the entrypoints: the `agent` engine's controller and
`GET /agent/v1/meta` carry `@ConditionalOnAgentEngine`. Switching the mode back to `workflow`
removes them entirely, so a routing verification done in `agent` mode is not reproducible there.

Because both engines render node text into a prompt, **`description` is the routing lever in both
modes** — it is the one field worth writing carefully. Write the boundary against neighbouring
tools explicitly ("answers X, not Y"); when two tools overlap (e.g. a detail tool that returns a
price and a stock tool that also returns a product name) an empty description makes the model drift
between them.

## MCP Server Cannot Be Started After the Client

`McpClientAutoConfiguration` connects, `initialize()`s and `listTools()`s in `@PostConstruct`, then
registers each tool. A failed connection is logged and **skipped — no retry, no startup failure**.
Start the MCP server first, or the tools never register and the whole chain fails silently.
