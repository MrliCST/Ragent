#!/usr/bin/env python3
"""
谷粒边界安全验证（AC3 / AC4 / AC5 / AC8）——直接打 guli，绕过 ragent 与 mcp-server。

为什么直连：这些用例测的是**谷粒侧验签器与拦截器本身**。走 ragent 会让
mcp-server 的包装层与模型话术夹在中间，失败原因难以归因；直连时请求/响应就是
"guli 收到什么、回了什么"，判据干净。

⚠️ 判据形态（很容易搞错，实测确认）：
   谷粒的 GuliExceptionHandler 是 @RestControllerAdvice，BusinessException 走
   `R.fail(code, msg)`，**HTTP 状态码恒为 200**，未登录/越权体现在响应体的
   `code` 字段（10011=未登录 / 10026=无权访问），不是 HTTP 401/403。
   因此本脚本断言的是 body 里的 code。design.md 与 implement.md 里口语说的
   "401/403" 指的是这个业务码语义。
   另注：任何未预期异常会被兜底成 code=99999(未知错误)，所以 99999 不等于"被拒"，
   它表示**拦截器里有 bug**，要在 AC4-d 里当异常信号看。

用法:
    python3 verify-guli-boundary.py            # 跑全部用例
    python3 verify-guli-boundary.py ac3        # 只跑 AC3（服务身份调公共数据接口）
    python3 verify-guli-boundary.py ac4        # 只跑 AC4（篡改/过期/aud/alg/kid）
    python3 verify-guli-boundary.py ac5 ac8    # 只跑越权与真人登录回归
"""
import json
import pathlib
import subprocess
import sys
import time

SHOPCART = "http://localhost:9216"     # guli-shopcart：C 端，/cart 在白名单内
PRODUCT = "http://localhost:9214"      # guli-product：/maintain 是后台接口，不在白名单

PRIVATE_KEY = pathlib.Path.home() / ".ragent-delegation/delegation-private.pem"
PUBLIC_KEY = pathlib.Path.home() / ".ragent-delegation/delegation-public.pem"
KID = "ragent-2026-09"

ADMIN_USER = "2001523723396308993"     # admin → member 1
# mcp-server 的服务身份令牌（mcp-server/src/main/resources/application.yml
# 的 guli.auth.token）。AC3 用它证明"改造没把原有的服务身份通道挤掉"。
SERVICE_TOKEN = "mcp-live-test-token-0001"
ISS = "ragent"
AUD = "guli-mall"

UNAUTHORIZED = 10011
FORBIDDEN = 10026
UNKNOWN_ERROR = 99999
# 谷粒的 R.SUCCESS = 200（ruoyi-common-core 的 R.java:25），不是 0。
# 于是"成功"与 HTTP 状态码同为 200 —— 这制造了一个很危险的断言陷阱：
#   if http_status == 200: 视为成功   →   永远为真，**每一次拒绝都会被判成通过**。
# 必须断言 body 里的 code。本脚本全部按 code 断言，正是为了避开这一点。
SUCCESS = 200
FAIL = 500


def claims(**over):
    now = int(time.time())
    c = {"iss": ISS, "aud": AUD, "sub": f"ragent:{ADMIN_USER}",
         "session": "boundary-test", "scope": ["cart:read"],
         "iat": now, "exp": now + 60, "jti": f"boundary-{now}"}
    c.update(over)
    return c


def sign(payload, kid=KID, alg="RS256", key=None):
    import jwt
    return jwt.encode(payload, key or PRIVATE_KEY.read_bytes(), algorithm=alg,
                      headers={"kid": kid})


def call(base, path, token=None, raw_header=None, method="GET"):
    """返回 (http_status, body_code, body_msg)

    ⚠️ 每次调用前必须删掉 body 文件：curl 连接失败（HTTP 0）时不会写它，
    上一次的响应体会留在原地被当成这次的响应 —— 会把"没连上"误读成"上一次的结果"。
    （我第一版就踩了这个：shopcart 还没起来时 /cartXYZ 读到了 product 上一次的 404。）
    """
    body_path = pathlib.Path("/tmp/_gb_body.json")
    body_path.unlink(missing_ok=True)
    args = ["curl", "-s", "-o", str(body_path), "-w", "%{http_code}", "-m", "15",
            "-X", method, f"{base}{path}"]
    if raw_header is not None:
        args += ["-H", f"Authorization: {raw_header}"]
    elif token is not None:
        args += ["-H", f"Authorization: Bearer {token}"]
    status = subprocess.run(args, capture_output=True, text=True, timeout=20).stdout.strip()
    if not body_path.exists():
        return (int(status) if status.isdigit() else -1), None, "（无响应体：连接失败）"
    try:
        body = json.loads(body_path.read_text())
        return int(status), body.get("code"), body.get("msg")
    except Exception:
        return int(status) if status.isdigit() else -1, None, "（响应体非 JSON）"


def redis_exists(key):
    return subprocess.run(
        ["docker", "exec", "guli-redis", "redis-cli", "-a", "ruoyi123",
         "--no-auth-warning", "EXISTS", key],
        capture_output=True, text=True, timeout=30).stdout.strip()


def read_body():
    """读回 call() 刚写到 /tmp 的响应体（用于需要看字段而非只看 code 的用例）"""
    try:
        return json.loads(pathlib.Path("/tmp/_gb_body.json").read_text())
    except Exception:
        return None


def check(name, expect_code, base, path, token=None, raw_header=None, forbid_success=True):
    status, code, msg = call(base, path, token, raw_header)
    ok = (code == expect_code)
    if forbid_success and expect_code != SUCCESS:
        ok = ok and code != SUCCESS
    print(f"[{'OK  ' if ok else 'FAIL'}] {name}: HTTP {status}, code={code}, msg={msg} "
          f"(期望 code={expect_code})")
    return ok


def main():
    cmds = sys.argv[1:] or ["ac3", "ac4", "ac5", "ac8"]
    results = []

    if "ac4" in cmds:
        print("--- AC4：验签与声明校验（任一不通过都必须落 UNAUTHORIZED，绝不放行）---")
        valid = sign(claims())

        # 基线：合法令牌 + 白名单内路径。绑定与开关就位时应为 code=0。
        # 若此项失败而其它项"正确地"失败，说明不是安全拦住了，而是链路本身没通。
        results.append(check("AC4 基线 合法令牌→/cart", SUCCESS, SHOPCART, "/cart", valid))

        # a) 篡改签名：改掉签名段最后一个字符
        head, payload_b64, sig = valid.split(".")
        tampered = f"{head}.{payload_b64}.{sig[:-1]}{'A' if sig[-1] != 'A' else 'B'}"
        results.append(check("AC4-a 篡改签名", UNAUTHORIZED, SHOPCART, "/cart", tampered))

        # b) 过期
        results.append(check("AC4-b 已过期 exp", UNAUTHORIZED, SHOPCART, "/cart",
                             sign(claims(iat=int(time.time()) - 600,
                                         exp=int(time.time()) - 300))))

        # c) aud 不符（防止别的系统的令牌被拿来调谷粒）
        results.append(check("AC4-c aud 不符", UNAUTHORIZED, SHOPCART, "/cart",
                             sign(claims(aud="other-system"))))

        # c2) iss 不符
        results.append(check("AC4-c2 iss 不符", UNAUTHORIZED, SHOPCART, "/cart",
                             sign(claims(iss="other-issuer"))))

        # d) sub 格式不符（必须带 ragent: 前缀）
        results.append(check("AC4-d sub 缺前缀", UNAUTHORIZED, SHOPCART, "/cart",
                             sign(claims(sub=ADMIN_USER))))

        # e) alg=none 降级攻击：手工拼一个无签名令牌（PyJWT 的 encode 要求 key=None）
        import base64
        b64 = lambda d: base64.urlsafe_b64encode(
            json.dumps(d, separators=(",", ":")).encode()).rstrip(b"=").decode()
        none_tok = f"{b64({'alg':'none','kid':KID})}.{b64(claims())}."
        results.append(check("AC4-e alg=none 降级", UNAUTHORIZED, SHOPCART, "/cart", none_tok))

        # f) 未知 kid
        results.append(check("AC4-f 未知 kid", UNAUTHORIZED, SHOPCART, "/cart",
                             sign(claims(), kid="unknown-kid-2099")))

        # g) 完全无 Authorization 头
        results.append(check("AC4-g 无 Authorization 头", UNAUTHORIZED, SHOPCART, "/cart"))

        # h) 用真实私钥签、但 scope 为空（纵深防御：无授权范围直接拒）
        #    注意：这一条期望 UNAUTHORIZED 还是 FORBIDDEN 取决于实现——
        #    AgentScopePolicy.allows 对空 scope 返回 false → 走 FORBIDDEN。
        results.append(check("AC4-h 空 scope", FORBIDDEN, SHOPCART, "/cart",
                             sign(claims(scope=[]))))

    if "ac5" in cmds:
        print("\n--- AC5：代理令牌不得触达后台管理接口（默认拒绝）---")
        valid = sign(claims())

        # ⚠️ 只有"有真实映射"的路径才能测越权：
        #    DispatcherServlet 要先解析出 handler 才会跑 preHandle。路径若无映射（404）、
        #    方法不符（405）、或 handler bean 建不起来（500），**拦截器根本没运行**，
        #    那些状态码不是"被策略拒绝"，不能计入 AC5 通过。
        #    实测：/maintain/brands 在本环境返回 500 —— PmsSpuController 因
        #    BaseEsMapper<SkuEsModel> 缺失而建不起来（既有 ES 环境问题，与本改造无关），
        #    故该路径不可用，改用下面三个能真实到达的。
        results.append(check("AC5 后台 /category/listTree", FORBIDDEN, PRODUCT,
                             "/category/listTree", valid))
        results.append(check("AC5 后台 /brand/list", FORBIDDEN, PRODUCT,
                             "/brand/list", valid))
        results.append(check("AC5 后台 /attribute/attrGroup/list", FORBIDDEN, PRODUCT,
                             "/attribute/attrGroup/list", valid))

        # 正面用例：白名单内的 C 端路径**必须放行**。
        # 没有这条，上面三条"全拒"可能只是链路坏了而非策略生效。
        results.append(check("AC5 白名单内 /display/item/1 应放行", SUCCESS, PRODUCT,
                             "/display/item/1", valid, forbid_success=False))

        # 以下两条策略边界**无法用 HTTP 测**，原因见上：它们依赖"有前缀但无对应映射"
        # 的路径（/cartXYZ、/display/item/），Spring 会先 404，拦截器不运行。
        # 这两条由 AgentScopePolicy 的单元级验证覆盖（S4 已对 allows() 逐条断言），
        # 不要在此处用 HTTP 结果冒充。
        print("[注] 策略边界（精确路径不退化 /cartXYZ、前缀后须有内容 /display/item/）"
              "无对应映射，HTTP 层不可达，由 AgentScopePolicy 单元验证覆盖")

    if "ac3" in cmds:
        print("\n--- AC3：公共数据接口不退化（服务身份仍可查）---")
        # 服务身份 = mcp-server 自己的 token（mcp-server/application.yml 的 guli.auth.token）。
        # 它**不是 JWT**，是真人登录态 —— 因此 verify() 返回 null，落拦截器分支二，
        # 也就是"改造前怎么走、现在还得怎么走"的那条路。这正是 AC3 要证的：
        # 代理令牌上线没有把原有的服务身份通道挤掉。
        svc = SERVICE_TOKEN
        if redis_exists(f"guli:auth:{svc}") != "1":
            print(f"[SKIP] 服务身份登录态 {svc} 不存在（需重新种）")
        else:
            for sku in (1, 9):
                status, code, msg = call(PRODUCT, f"/display/item/{sku}", token=svc)
                # 只看 code=200 不够：空壳响应也是 200。必须验到真实商品名，
                # 才说明这个公共接口真的把数据查出来了（S8 的原始判据）。
                data = (read_body() or {}).get("data") or {}
                name = (data.get("skuInfo") or {}).get("skuName")
                ok = (code == SUCCESS and bool(name))
                print(f"[{'OK  ' if ok else 'FAIL'}] AC3 服务身份 /display/item/{sku}: "
                      f"HTTP {status}, code={code}, skuName={name!r} (期望 code={SUCCESS} 且有名)")
                results.append(ok)
            # 老行为不变：这个接口本来就要登录，没令牌仍必须拒
            results.append(check("AC3 无令牌仍 401", UNAUTHORIZED, PRODUCT, "/display/item/1"))

    if "ac8" in cmds:
        print("\n--- AC8：真人登录路径不得被改造破坏（分支二回归）---")
        # 种的两个真人登录态（implement.md 测试数据）。
        # ⚠️ bearer 值就是 seed 名本身，不是 Redis 里那个 JSON：
        #    真人分支查的是 `guli:auth:<token>`，所以 token="cart-seed-m1" 才命中。
        #    （把 GET 出来的 UserInfo JSON 当 token 发，会去查 `guli:auth:["com.atlearn...`，
        #      必然 10011 —— 那是测试自己写错，不是被测对象有问题。）
        for seed, member in [("cart-seed-m1", 1), ("cart-seed-m2", 2)]:
            if redis_exists(f"guli:auth:{seed}") != "1":
                print(f"[SKIP] 真人登录态 {seed} 不存在（需重新种）")
                continue
            status, code, msg = call(SHOPCART, "/cart", token=seed)
            ok = (code == SUCCESS)
            print(f"[{'OK  ' if ok else 'FAIL'}] AC8 真人 {seed}→member{member} /cart: "
                  f"HTTP {status}, code={code}, msg={msg} (期望 code={SUCCESS})")
            results.append(ok)
        # 无令牌时的老行为不变
        results.append(check("AC8 无令牌仍 401", UNAUTHORIZED, SHOPCART, "/cart"))

    print()
    if results:
        print(f"通过 {sum(results)}/{len(results)}")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
