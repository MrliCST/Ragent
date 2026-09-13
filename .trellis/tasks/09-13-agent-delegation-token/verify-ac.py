#!/usr/bin/env python3
"""
AC1 / AC2 / AC6 端到端验证（代理令牌身份是否真的生效）。

为什么 AC2 是唯一有效判据（prd.md 原文）：
    「换一个会员做同样提问，返回的数据随之变化——证明身份真的在生效，而不是碰巧对上了。」
单会员场景下「串号」与「正常」结果完全相同，所以必须两个会员 + 两份不同的购物车。

测试夹具（种子数据，见 implement.md）：
    memberId=1  mcp_test     购物车 = SKU 1 华为 HUAWEI Mate 30 Pro 星河银  ¥6299
    memberId=2  mcp_probe2   购物车 = SKU 9 Apple iPhone 11 黑色 128GB     ¥5999
    memberId=3  mcp_disabled status=0（停用）→ 必须解析为 null → 401

判别标记选「华为/Mate」与「iPhone」，是因为两份菜名互不包含：
若出现对方的标记，说明取到了别人的购物车（串号），而不是「碰巧」。

用法:
    python3 verify-ac.py bind          # 种绑定（AC1/AC2 前置）
    python3 verify-ac.py ac1 ac2       # 跑 AC1 + AC2
    python3 verify-ac.py ac6           # 跑 AC6（会临时把 probe 改绑到停用会员 3，跑完改回）
    python3 verify-ac.py unbind        # 解绑（回滚点）
"""
import json
import re
import subprocess
import sys
import time
import urllib.parse

BASE = "http://localhost:9090/api/ragent"
# 身份证据取谷粒侧（guli-shopcart）的审计日志，而不是 mcp-server 的日志：
#   mcp-server 是透传方，它只能打印令牌里**未经校验**的 sub；谷粒侧这行是
#   验签通过并查完绑定之后的结果，同时给出解析出的 memberId——证据强一个量级。
#   （S9 已按计划移除 mcp-server 的临时探针日志，见 AgentDelegation.java。）
GULI_LOG = "/tmp/guli-shopcart.log"

# 代理调用通过, ragentUserId: <ragent 用户>, session: <会话>, memberId: <解析出的会员>, uri: <路径>
RESOLVED_RE = re.compile(
    r"代理调用通过, ragentUserId: (\d+), session: (\S+?), memberId: (\d+), uri: (\S+)")
# 代理令牌未绑定可用会员, ragentUserId: <ragent 用户>, session: <会话>
UNRESOLVED_RE = re.compile(r"代理令牌未绑定可用会员, ragentUserId: (\d+), session: (\S+)")

# 绑定的 Redis key 前缀与值形态，以 S4 的实现为准：
#   RedisAgentBindingResolver.KEY_PREFIX / parse()
#   key   = guli:agent-binding:<裸 ragentUserId>（sub 去掉 "ragent:" 前缀）
#   value = {"memberId":N,"active":bool}；仅 active=true 且 memberId 非空才放行
# active 是 AC6 的载体：绑定不能只是裸 memberId，否则「未绑定」与「已绑定但会员停用」
# 无法区分。（design.md 里写的裸 <memberId> 已被实现取代，见 AgentBindingResolver 接口注释。）
BINDING_PREFIX = "guli:agent-binding:"

# 绑定解析在谷粒侧有 10s 本地缓存（含负缓存）：种完绑定必须等它过期，
# 否则刚种的数据会被"未绑定"的旧缓存盖住，表现为莫名其妙的 401。
BINDING_CACHE_TTL = 10

# ragent 用户 id → 谷粒 memberId
ADMIN_USER = "2001523723396308993"   # admin     → member 1
PROBE_USER = "2001523723396308994"   # probe_user → member 2
DISABLED_MEMBER = 3                  # mcp_disabled

QUESTION = "我的购物车有什么"

HUAWEI_MARK = "Mate 30 Pro"
IPHONE_MARK = "iPhone 11"


def redis(*args):
    return subprocess.run(
        ["docker", "exec", "guli-redis", "redis-cli", "-a", "ruoyi123",
         "--no-auth-warning", *args],
        capture_output=True, text=True, timeout=30).stdout.strip()


def bind(user_id, member_id, active=True):
    redis("SET", f"{BINDING_PREFIX}{user_id}",
          json.dumps({"memberId": member_id, "active": active}))


def unbind(user_id):
    redis("DEL", f"{BINDING_PREFIX}{user_id}")


def login(username, password):
    out = subprocess.run(
        ["curl", "-s", "-X", "POST", f"{BASE}/auth/login",
         "-H", "Content-Type: application/json",
         "-d", json.dumps({"username": username, "password": password})],
        capture_output=True, text=True, timeout=30).stdout
    return json.loads(out)["data"]["token"]


def ask(token):
    """发一次对话，返回拼起来的模型回答全文"""
    url = f"{BASE}/agent/v1/chat?" + urllib.parse.urlencode({"question": QUESTION})
    out = subprocess.run(
        ["curl", "-N", "-s", "-m", "180", "-H", f"Authorization: {token}", url],
        capture_output=True, text=True, timeout=200).stdout
    deltas = re.findall(r'^data:\{"type":"response","delta":"(.*)"\}$', out, re.M)
    return "".join(deltas)


def _read_since(offset):
    """读日志自 offset 起的新内容。

    日志被重建（服务重启）时 offset 会越过 EOF，seek 之后读不到东西，
    表现为"明明调用了却查不到证据"——所以越过就退回从头读。
    """
    with open(GULI_LOG, "rb") as f:
        f.seek(0, 2)
        if offset > f.tell():
            offset = 0
        f.seek(offset)
        return f.read().decode("utf-8", errors="replace")


def resolutions_since(offset):
    """谷粒侧本次窗口内"验签 + 绑定解析通过"的调用：(ragentUserId, memberId, uri)"""
    return RESOLVED_RE.findall(_read_since(offset))


def denials_since(offset):
    """谷粒侧本次窗口内"令牌有效但没解析出可用会员"的调用：(ragentUserId, session)"""
    return UNRESOLVED_RE.findall(_read_since(offset))


def log_size():
    with open(GULI_LOG, "rb") as f:
        f.seek(0, 2)
        return f.tell()


def report(name, ok, detail):
    print(f"[{'OK  ' if ok else 'FAIL'}] {name}: {detail}")
    return ok


def run_case(name, token, expect_mark, forbid_mark, expect_user, expect_member):
    """一个 AC 用例：问一次，同时校验「模型答案」与「谷粒侧解析出的身份」两处证据

    两条证据缺一不可：
      - 答案：用户看到的结果对不对（含自己的商品、不含别人的）
      - 谷粒侧解析记录：**为什么**对——是验签后按绑定解析出了预期会员，
        而不是模型碰巧编对了。单看答案无法区分"链路通了"和"串号了"。
    """
    offset = log_size()
    answer = ask(token)
    resolved = resolutions_since(offset)

    ok_ans = expect_mark in answer
    ok_forbid = forbid_mark not in answer
    # r = (ragentUserId, session, memberId, uri)：会员是第 3 组，别错拿会话去比
    hit = [r for r in resolved
           if r[0] == expect_user and r[2] == str(expect_member)]
    ok_ident = bool(hit)

    detail = (f"答案含「{expect_mark}」={ok_ans}, 不含「{forbid_mark}」={ok_forbid}, "
              f"谷粒解析={'/'.join(f'{r[0]}→member{r[2]}' for r in resolved) or '无'}"
              f" (期望 {expect_user}→member{expect_member})")
    if not (ok_ans and ok_forbid):
        detail += f"\n       答案原文: {answer[:300]}"
    return report(name, ok_ans and ok_forbid and ok_ident, detail)


def main():
    cmds = sys.argv[1:] or ["ac1", "ac2"]

    if "bind" in cmds:
        bind(ADMIN_USER, 1)
        bind(PROBE_USER, 2)
        print(f"已种绑定: {ADMIN_USER}→1, {PROBE_USER}→2")
        print(f"  (回读: {redis('GET', BINDING_PREFIX + ADMIN_USER)})")
        print(f"  注意：谷粒侧绑定有 {BINDING_CACHE_TTL}s 本地缓存，"
              f"请等待后再跑 AC，否则会读到旧的「未绑定」负缓存。")
        return 0

    if "unbind" in cmds:
        unbind(ADMIN_USER)
        unbind(PROBE_USER)
        print("已解绑（回滚点）")
        return 0

    results = []

    if "ac1" in cmds:
        tok = login("admin", "admin")
        results.append(run_case("AC1 admin→member1", tok, HUAWEI_MARK, IPHONE_MARK,
                                ADMIN_USER, 1))

    if "ac2" in cmds:
        tok = login("probe_user", "probe123456")
        results.append(run_case("AC2 probe→member2", tok, IPHONE_MARK, HUAWEI_MARK,
                                PROBE_USER, 2))

    if "ac6" in cmds:
        # 停用会员：临时把 probe 改绑到 member 3 并标 active=false
        # （member 3 = mcp_disabled, status=0；Redis 实现用 active 表达，
        #   将来 MySQL 实现必须由 UmsMember.status==1 计算，见接口注释）
        bind(PROBE_USER, DISABLED_MEMBER, active=False)
        time.sleep(BINDING_CACHE_TTL + 2)  # 等谷粒侧负缓存/旧缓存过期
        tok = login("probe_user", "probe123456")
        offset = log_size()
        answer = ask(tok)
        resolved = resolutions_since(offset)
        denied = denials_since(offset)
        # AC6 期望：解析不到会员 → 谷粒 401/未登录；绝不能拿到任何会员的购物车
        leaked = (HUAWEI_MARK in answer) or (IPHONE_MARK in answer)
        # 判据必须是"令牌有效但解析不出会员"这一条**专属**日志，而不是答案里的
        # "登录"字样——后者模型自己也可能说，那样的"通过"是假通过。
        ok_deny = bool([d for d in denied if d[0] == PROBE_USER])
        ok_no_resolve = not [r for r in resolved if r[0] == PROBE_USER]
        results.append(report(
            "AC6 probe→停用会员 3", ok_deny and ok_no_resolve and not leaked,
            f"谷粒专属拒绝日志={ok_deny}, 未解析出会员={ok_no_resolve}, "
            f"未泄露任何会员购物车={not leaked}"))
        if leaked:
            results.append(report("AC6 泄露检查", False, f"答案原文: {answer[:300]}"))
        bind(PROBE_USER, 2)  # 复原，避免影响后续 AC2 复跑
        time.sleep(BINDING_CACHE_TTL + 2)

    print()
    print(f"通过 {sum(results)}/{len(results)}")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
