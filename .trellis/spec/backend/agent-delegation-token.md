# Agent Delegation Token: ragent Calling Guli as the End User

> How ragent calls guli-mall APIs **as the end user** instead of as one shared service account —
> an OAuth2 RFC 8693-style on-behalf-of delegation, with the identity mapping authority on the
> guli side.

---

## Scenario: Make a downstream call carry the end user's identity

### 1. Scope / Trigger

Trigger this spec when **any** of the following is true:

- You add an MCP tool that calls guli and the result depends on **who is asking** (cart, orders,
  anything scoped to `UserInfoContext.getUserId()`)
- You change the delegation token's claims, header name, or TTL
- You change `UserInfoInterceptor` in `guli-common` — the single injection point for the whole site
- You are debugging "the call succeeded but returned another user's data" (identity crossed over)
- You rotate the signing key (`kid`)

The trap this spec prevents: a tool that "works" while silently answering with the **service
account's** data. The chain is green, the answer looks plausible, and every single-user test passes.
Only a **second member** exposes it — see §6.

---

## Two Project Conventions (the reason this spec exists)

### Convention 1 — The delegation token travels in the **MCP transport layer**, never in tool parameters

**What**: the token is written to the HTTP header `X-Agent-Delegation` by the MCP client, read back
from `McpTransportContext` by the server, and forwarded to guli as
`Authorization: Bearer <jwt>`. At no point is it an argument of the tool call.

**Why**: **tool parameters are model-generated JSON.** The model sees them, can repeat them in its
answer, and a prompt injection can talk it into rewriting them. They are not a trusted channel.
A token placed there is a token the model can leak, forge, or attach to the wrong user's call.

**Example** (the full chain, each hop in a different module):

```
agent:   McpToolBridge.execute(param)
           jwt = issuer.issue(runtimeContext.getUserId(), runtimeContext.getSessionId())
           DelegationContext.set(jwt)                    // ThreadLocal bridge
           └─ McpSyncClient.callTool(...)
                ├─ transportContextProvider → DelegationContext.snapshot()
                └─ httpRequestCustomizer    → header X-Agent-Delegation
                          ↓ HTTP
mcp-server: exchange.transportContext().get(AgentDelegation.CONTEXT_KEY)
           └─ GuliApiSupport.headers(properties, jwt) → Authorization: Bearer <jwt>
                          ↓ HTTP
guli:    UserInfoInterceptor → AgentJwtVerifier + AgentBindingResolver → UserInfoContext
```

`mcp-server` is a **pure pass-through**: it holds no public key and cannot verify anything. That is
deliberate — verification authority lives in exactly one place (guli).

**Related**: `DelegationContext`, `AgentDelegation`, `GuliApiSupport`.

### Convention 2 — Guli-side verification is **dual-branch and must never degrade on failure**

**What**: `UserInfoInterceptor.preHandle` tries the delegation branch first; if it fails for **any**
reason, control falls through to the **original Redis branch**, which can still reject. There is no
path where a failed delegation attempt yields an anonymous-but-allowed request.

**Why**: "JWT parse failed → treat as anonymous and let it through" is the classic vulnerability of
this whole class of change. It turns every malformed token into a valid one. Failures must be
**inert**: the only outcomes are "a real identity" or `UNAUTHORIZED`.

The two branches must also stay **independent implementations**. The delegation branch exists so
that verification does not depend on stateful storage; reusing the Redis lookup for it would defeat
the point.

**Related**: `UserInfoInterceptor`, `AgentJwtVerifier`.

---

### 2. Signatures

**Issuer — ragent** (`agent/src/main/java/com/nageoffer/ai/ragent/agent/delegation/DelegationTokenIssuer.java:69`):

```java
public String issue(String ragentUserId, String sessionId)   // null/blank on failure → caller falls back
```

**Transport carrier** (`rag/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/DelegationContext.java`):

```java
public static final String HEADER = "X-Agent-Delegation";
public static final String KEY    = HEADER;
public static void set(String token);        // null clears
public static String get();
public static void clear();                  // MUST be in finally
public static McpTransportContext snapshot(); // EMPTY when no token
```

**Pass-through — mcp-server** (`mcp-server/.../config/AgentDelegation.java:51`):

```java
public static String tokenOf(McpSyncServerExchange exchange)   // null when absent
```

**Verifier — guli** (`guli-common/.../agent/AgentJwtVerifier.java:82`):

```java
public AgentTokenClaims verify(String token)   // null on ANY failure; never throws, never passes through
```

**Binding resolver — guli** (`guli-common/.../agent/AgentBindingResolver.java:28`):

```java
Long resolve(String ragentUserId);             // null ⇒ unbound OR member disabled
```

**Scope policy — guli** (`guli-common/.../agent/AgentScopePolicy.java`):

```java
public static boolean allows(List<String> scope, String requestUri);   // default-deny
```

### 3. Contracts

**Header** (ragent → mcp-server): `X-Agent-Delegation: <jwt>` — raw JWT, no `Bearer ` prefix.
**Header** (mcp-server → guli): `Authorization: Bearer <jwt>` — same header a human login uses,
different semantics; guli tells them apart by trying `verify()` first.

**Claims** (signed RS256, header carries `kid`):

| Claim | Type | Constraint |
|---|---|---|
| `iss` | string | must equal `ragent`; **verified** |
| `aud` | string | must equal `guli-mall`; **verified** — without it, another system's token works here |
| `sub` | string | must be `ragent:<ragentUserId>`; the prefix is required |
| `session` | string | `RuntimeContext.getSessionId()` = conversationId (audit only) |
| `scope` | string[] | path whitelist basis; **empty ⇒ rejected** |
| `iat` / `exp` | number | TTL 60s; **±30s clock skew tolerated** |
| `jti` | string | uuid; becomes `UserInfo.userKey = "agent:<jti>"` |

> **`sub` deliberately carries no `memberId`.** The ragent user id is all ragent may assert; turning
> it into a guli member is guli's decision. This is what makes ragent unable to impersonate an
> arbitrary member even if fully compromised.

**Binding store** (Redis, test fixture for now):

```
key   guli:agent-binding:<ragentUserId>       ← raw id, no "ragent:" prefix
value {"memberId":<Long>,"active":<bool>}     ← NOT a bare memberId
```

Only `active == true` **and** a non-null `memberId` resolve to a member. The object shape exists so
that "never bound" and "bound but the member was disabled" stay distinguishable — AC6 is exactly
that distinction. A future MySQL implementation must **compute** `active` from `UmsMember.status == 1`
rather than store a flag that can drift.

Resolution is cached locally for **10s, including negative results**. After changing a binding you
must wait out the TTL or you will read a stale "unbound" (symptom: a mysterious 401).

**Config keys**

| Side | Key | Default | Note |
|---|---|---|---|
| ragent | `agent.delegation.enabled` | `false` | `AGENT_DELEGATION_ENABLED` |
| ragent | `agent.delegation.private-key-path` | — | PEM **outside the repo**; never committed |
| ragent | `agent.delegation.key-id` | `ragent-2026-09` | rotates together with the guli public key |
| ragent | `agent.delegation.issuer` / `.audience` | `ragent` / `guli-mall` | must match the verifier |
| ragent | `agent.delegation.ttl-seconds` | `60` | one MCP round trip is ~32ms; 60s is already wide |
| ragent | `agent.delegation.scope` | `cart:read` | comma-separated |
| guli | `guli.agent-token.enabled` | `false` | branch 1 skipped entirely when off |
| guli | `guli.agent-token.keys.<kid>` | — | PEM; a **map**, so rotation keeps old + new alive |

**Why RS256 and not HS256**: with a shared secret, **any** compromised guli module (there are a
dozen) could mint a valid delegation token. Asymmetric means the guli side has no signing capability
at all. Cost: key files, distribution, `kid` rotation.

**Issuance granularity — per tool call, not per run.** A run can spend minutes in model reasoning;
a run-scoped token would either need a long TTL (wide exposure) or expire mid-run (hard to debug).
Per-call signing keeps the window at 60s and costs a sub-millisecond RSA sign.

### 4. Validation & Error Matrix

| Condition | Result |
|---|---|
| valid signature + known `kid` + `iss`/`aud`/`sub`/`exp` ok + scope allows + bound + `active` | `UserInfoContext.userId = memberId`, request proceeds |
| tampered signature | `verify()` → null → **branch 2** → `10011` |
| `exp` past (beyond 30s skew) | same → `10011` |
| `aud` mismatch | same → `10011` |
| `iss` mismatch | same → `10011` |
| `sub` without `ragent:` prefix | same → `10011` |
| `alg: none` downgrade | same → `10011` |
| unknown `kid` | same → `10011` |
| no `Authorization` header | `10011` before either branch |
| signature valid but `scope` empty | `10026` (fail-closed, deeper than signature) |
| valid token, path **not** in whitelist | `10026` |
| valid token, resolve() → null (unbound **or** member `status != 1`) | `10011` |
| `enabled = false` on the guli side | branch 1 never runs; behaviour identical to before the change |

> **Warning — guli answers `HTTP 200` even when it denies.**
> `GuliExceptionHandler` is an `@RestControllerAdvice`, and `R.SUCCESS = 200` /
> `R.FAIL = 500` (`ruoyi-common-core/.../domain/R.java:25`). So success is `HTTP 200 + code 200`,
> denial is `HTTP 200 + code 10011/10026`, and `if http_status == 200` **passes every denial**.
> Always assert `body.code`. `99999` (unknown error) additionally means a **bug in the
> interceptor**, not a rejection.

### 5. Good/Base/Bad Cases

- **Good** — a tool that needs the caller's identity takes it from
  `param.getRuntimeContext()`, signs per call, and the guli side resolves the member itself.
- **Base** — before believing any "denied" result, first make the **baseline** pass: a legitimately
  signed token on a whitelisted path must return `code=200`. Otherwise you cannot tell "security
  blocked it" from "the chain was never connected".
- **Bad** — putting the token (or the memberId, or the guli username) into the tool's parameter
  schema; letting `verify()` failure fall through to *allow*; writing the scope list as a
  blacklist; keeping `enabled = true` while the guli public key config is wrong (all agent calls
  401, and it looks like a signing bug).

### 6. Tests Required

Two members are **mandatory** — with one member, "identity works" and "identity crossed over" are
indistinguishable, because both answer with that member's data.

1. **AC1/AC2 — the only valid identity judgement.** Give member 1 and member 2 carts whose contents
   are mutually exclusive (e.g. "Mate 30 Pro" vs "iPhone 11"). Ask the same question as each. Assert
   **both**: the answer contains your own marker and **not** the other's, **and** the guli log shows
   `代理调用通过, ragentUserId: <expected>, ..., memberId: <expected>`. The log line is what
   separates "resolved the right member" from "the model guessed right".
   *Assertion point*: `memberId` parsed from the **guli** log — not the `sub` printed by mcp-server,
   which is unverified pass-through.

2. **AC4 — verification must not degrade.** Baseline first (valid token → `code=200`), then each of:
   tampered signature, expired, wrong `aud`, wrong `iss`, `sub` without prefix, `alg=none`,
   unknown `kid`, no header → all `10011`; empty scope → `10026`. *Assertion point*: `body.code`,
   never HTTP status.

3. **AC5 — default-deny.** Admin/backend paths with a valid token → `10026`; a whitelisted C-end path
   → `code=200` (without the positive case, three denials prove only that the chain is broken).
   *Assertion point*: the admin paths must have **real handler mappings** — an unmapped path 404s
   (or 500s) before `preHandle` runs, and that status is not a policy decision.

4. **Concurrency — no cross-talk.** Two different users start a conversation **simultaneously**;
   each round must yield exactly two tokens whose `sub` values are the two distinct user ids.
   Cross-talk shows up as one id appearing twice and the other vanishing.
   This is required because `DelegationContext`'s ThreadLocal bridges to the SDK's
   `transportContextProvider` whose **evaluation thread is an SDK internal detail** — the whole
   design rests on `McpSyncClient.callTool` blocking on the calling thread. If an SDK upgrade
   introduces `subscribeOn`, user A's token starts appearing in user B's call.

5. **AC3 / AC8 — regressions.** Service identity (a non-JWT token) still reaches public data
   (`/display/item/{id}` returns a real product name, not just `code=200`); real human logins still
   work on both branches.

### 7. Wrong vs Correct

#### Wrong

```java
// (a) token as a tool parameter — model-generated, prompt-injectable
tool schema: { "skuId": int, "agentToken": string }   // ❌ the model can rewrite this

// (b) verification failure degrades to anonymous — every malformed token now succeeds
AgentTokenClaims claims = verifier.verify(token);
if (claims == null) {
    return true;                                       // ❌ the classic vulnerability
}

// (c) scope as a blacklist
if (requestUri.startsWith("/maintain")) return false;  // ❌ everything else slips through

// (d) bare memberId as the binding value — AC6 becomes untestable
SET guli:agent-binding:<uid> 8                          // ❌ unbound vs disabled are now identical
```

#### Correct

```java
// (a) transport layer only
DelegationContext.set(issuer.issue(userId, sessionId));
try { executor.execute(args); } finally { DelegationContext.clear(); }   // ✅ finally, not after

// (b) failure is inert: fall through to branch 2, which can still reject
AgentTokenClaims claims = agentJwtVerifier.verify(token);   // null on any failure
if (claims != null) {
    if (!AgentScopePolicy.allows(claims.getScope(), request.getRequestURI())) {
        throw new BusinessException(ErrorCodeEnum.FORBIDDEN);
    }
    Long memberId = agentBindingResolver.resolve(claims.getRagentUserId());
    if (memberId == null) {
        throw new BusinessException(ErrorCodeEnum.UNAUTHORIZED);   // unbound OR disabled
    }
    // ... set UserInfoContext
    return true;
}
// branch 2 — unchanged original behaviour, real logins only
UserInfo userInfo = RedisUtils.getCacheObject(AUTH_TOKEN_KEY_PREFIX + token);
if (userInfo == null) {
    throw new BusinessException(ErrorCodeEnum.UNAUTHORIZED);       // ✅ still rejects
}

// (c) whitelist, exact or directory-prefix, with content required after the prefix
private static final Set<String>  EXACT_PATHS  = Set.of("/cart", "/order/submit", "/display/esearch");
private static final List<String> PREFIX_PATHS = List.of("/addCart/", "/display/item/");
// ✅ length() > prefix.length() so the bare prefix "/display/item/" is not itself allowed

// (d) object value keeps the two failure modes apart
SET guli:agent-binding:<uid> '{"memberId":8,"active":true}'   // ✅ AC6 is now testable
```

---

## Gotchas

> **Warning — the intent-tree cache masks newly added tools.** Adding a cart tool does not make it
> reachable; see `mcp-tool-routing.md`. Unrelated to delegation but hit in the same session.

> **Warning — a stale half-dead TCP connection can hang a model call for the full 5-minute model
> timeout**, with no stack trace and no output, while the process stays healthy. `ss -tnp` shows an
> ESTAB connection with a large `Send-Q` (data queued, never ACKed) to the provider; the HTTP client
> pool keeps reusing it. Restarting the process clears it. **This is not a delegation defect** — but
> it is easy to misread as one, since the symptom is "my new code stopped producing tokens".
>
> Its second-order effect is worse: the hung run never reaches its finalizer, so
> `AgentRunGate`'s `ragent:agent:running:<userId>` key (TTL = `sse-timeout-ms × 2`) holds for the
> full 30 minutes, and that user gets "当前会话处理中" for every new conversation until it expires.
> See task `09-13-agent-stream-failure-hardening`.

> **Warning — `mvn package -DskipTests` still *compiles* tests.** A stale test referencing a deleted
> class fails the build, and Maven then reports `repackage failed: Unable to find main class`, which
> points nowhere near the real cause. Use `-Dmaven.test.skip=true` to skip compilation too.

## Known Limitations

- **No permission model in the token** — `scope` is coarse path control only. Per-resource ownership
  (an order may only be read by its owner) remains each business endpoint's own job.
- **No immediate revocation** — a token stays valid within its 60s TTL after logout/ban. No `jti`
  blacklist, because that would reintroduce stateful verification.
- **Identity is not propagated across guli's internal services** — `DubboRequestFilter` only logs.
  If order → ware ever needs the identity, Dubbo attachments must carry it.
- **The ThreadLocal bridge depends on MCP SDK 1.1.2's internal threading** (see test 4).
