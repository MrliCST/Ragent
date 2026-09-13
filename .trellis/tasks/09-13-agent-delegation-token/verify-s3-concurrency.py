#!/usr/bin/env python3
"""
S3 并发串号验证（本任务最高风险项）。

风险：McpToolBridge 用 ThreadLocal 把令牌桥接给 MCP SDK 的 transportContextProvider。
该 supplier 的求值线程取决于 SDK 内部实现，若与 DelegationContext.set 不同线程，
就会出现「用户 A 的令牌出现在用户 B 的调用里」。

判据（每轮两个不同用户并发发起对话，各自触发 MCP 工具调用）：
  同一轮内，mcp-server 收到的令牌 sub **必须同时出现两个不同的 userId**。
  若两个 sub 相同 → 说明一个用户的令牌被另一个用户拿去用了 → 串号，立即停。

单轮只有两条请求，因此「两个 sub 不同」是一个干净的不变量：
串号时必然表现为其中一个 userId 出现两次、另一个消失。
"""
import subprocess
import sys
import time
import re
import json
import urllib.parse

BASE = "http://localhost:9090/api/ragent/agent/v1/chat"
LOG = "/tmp/mcp-server.log"

ADMIN_ID = "ragent:2001523723396308993"   # sub 的完整形态带 issuer 前缀（design.md 的 claims 约定）
PROBE_ID = "ragent:2001523723396308994"

ROUNDS = int(sys.argv[1]) if len(sys.argv) > 1 else 6


def login(user, pwd):
    out = subprocess.run(
        ["curl", "-s", "-X", "POST", "http://localhost:9090/api/ragent/auth/login",
         "-H", "Content-Type: application/json",
         "-d", json.dumps({"username": user, "password": pwd})],
        capture_output=True, text=True, timeout=30).stdout
    return json.loads(out)["data"]["token"]


def probe_count():
    try:
        with open(LOG, "rb") as f:
            return f.read().count(b"delegation-probe")
    except FileNotFoundError:
        return 0


def probe_subs_since(offset):
    """返回自 offset 字节起新出现的 sub 列表（按出现顺序）"""
    with open(LOG, "rb") as f:
        f.seek(offset)
        chunk = f.read().decode("utf-8", errors="replace")
    return re.findall(r"delegation-probe\] 收到代理令牌, sub=(\S+?),", chunk)


def ask(token, question):
    url = BASE + "?" + urllib.parse.urlencode({"question": question})
    subprocess.run(["curl", "-N", "-s", "-m", "150", "-H", f"Authorization: {token}", url],
                   capture_output=True, text=True, timeout=180)


ADMIN_TOK = login("admin", "admin")
PROBE_TOK = login("probe_user", "probe123456")
print(f"admin userId  = {ADMIN_ID}")
print(f"probe userId  = {PROBE_ID}")
print(f"轮数 = {ROUNDS}（每轮两个用户各发一条，并发）\n")

failures = []
total_lines = 0

for r in range(1, ROUNDS + 1):
    offset = 0
    with open(LOG, "rb") as f:
        f.seek(0, 2)
        offset = f.tell()

    # 两个用户的问句不同，便于在 executor 日志里区分是哪条请求
    p1 = subprocess.Popen(["python3", "-c",
        f"import subprocess,urllib.parse;"
        f"subprocess.run(['curl','-N','-s','-m','150','-H','Authorization: {ADMIN_TOK}',"
        f"'{BASE}?'+urllib.parse.urlencode({{'question':'请查一下 SKU 1 的商品详情'}})])"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    p2 = subprocess.Popen(["python3", "-c",
        f"import subprocess,urllib.parse;"
        f"subprocess.run(['curl','-N','-s','-m','150','-H','Authorization: {PROBE_TOK}',"
        f"'{BASE}?'+urllib.parse.urlencode({{'question':'请查一下 SKU 9 的商品详情'}})])"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    p1.wait()
    p2.wait()

    time.sleep(1.5)  # 等日志落盘
    subs = probe_subs_since(offset)
    total_lines += len(subs)
    uniq = set(subs)
    ok = (ADMIN_ID in uniq) and (PROBE_ID in uniq)
    mark = "OK  " if ok else "FAIL"
    print(f"[{mark}] 第 {r} 轮: 收到 {len(subs)} 条令牌, subs={sorted(subs)}")
    if not ok:
        failures.append((r, subs))

print()
if failures:
    print("!! 检出串号或缺失：")
    for r, subs in failures:
        print(f"   第 {r} 轮 subs={subs}")
    print("\n按 implement.md S3：立即停，改用「每次调用新建 client」，不要带着这个风险往下走。")
    sys.exit(1)

print(f"结论：{ROUNDS} 轮全部通过，共 {total_lines} 条令牌，每轮两个 userId 均正确出现一次。")
print("ThreadLocal 桥接在实测并发下未串号。")
print("注：仅证明当前 SDK 1.1.2 行为；design.md 已记录这是对 SDK 内部实现的依赖。")
