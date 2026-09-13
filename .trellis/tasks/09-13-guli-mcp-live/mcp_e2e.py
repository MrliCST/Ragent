#!/usr/bin/env python3
"""MCP Streamable HTTP 端到端验收：initialize -> tools/list -> tools/call"""
import json
import sys
import urllib.request

URL = "http://localhost:9099/mcp"
SESSION = None


def rpc(method, params=None, notify=False):
    global SESSION
    body = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        body["params"] = params
    if not notify:
        body["id"] = 1
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if SESSION:
        headers["mcp-session-id"] = SESSION
    req = urllib.request.Request(
        URL, data=json.dumps(body).encode(), headers=headers, method="POST"
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        sid = resp.headers.get("mcp-session-id")
        if sid:
            SESSION = sid
        raw = resp.read().decode()
        status = resp.status
    if notify or not raw.strip():
        return status, None
    # Streamable HTTP 以 SSE 帧返回：id: / event: message / data: {...}
    frames = [
        json.loads(line[5:].strip())
        for line in raw.splitlines()
        if line.startswith("data:") and line[5:].strip()
    ]
    if frames:
        for frame in frames:
            if frame.get("id") == 1:
                return status, frame
        return status, frames[0]
    return status, json.loads(raw)


def call_tool(name, arguments):
    status, body = rpc("tools/call", {"name": name, "arguments": arguments})
    if body is None or "result" not in body:
        return status, None, json.dumps(body, ensure_ascii=False)
    result = body["result"]
    text = "\n".join(c.get("text", "") for c in result.get("content", []))
    return status, result.get("isError"), text


def main():
    status, body = rpc(
        "initialize",
        {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "e2e", "version": "1.0"},
        },
    )
    print(f"[initialize] HTTP {status} session={SESSION}")
    print(json.dumps(body.get("result", {}).get("serverInfo", {}), ensure_ascii=False))

    rpc("notifications/initialized", notify=True)

    status, body = rpc("tools/list")
    tools = body["result"]["tools"]
    print(f"\n[tools/list] HTTP {status} 共 {len(tools)} 个工具")
    for t in tools:
        print(f"  - {t['name']}: {t['description'][:40]}...")

    cases = [
        ("product_detail_query", {"skuId": 1}),
        ("product_stock_query", {"skuId": 1}),
        ("product_stock_query", {"skuId": 1, "wareId": 2}),
        ("product_detail_query", {"skuId": 0}),
        ("product_detail_query", {"skuId": 999999}),
        ("product_stock_query", {"skuId": 999999}),
    ]
    for name, args in cases:
        status, is_error, text = call_tool(name, args)
        print(f"\n{'=' * 60}")
        print(f"[tools/call] {name} {args} -> HTTP {status} isError={is_error}")
        print("-" * 60)
        print(text)


if __name__ == "__main__":
    sys.exit(main())
