#!/usr/bin/env bash
#
# 手工种入谷粒商城的登录态 token。
#
# 为什么需要它：guli-common 的 UserInfoInterceptor 是这两个 MCP 工具唯一的鉴权关口，
# 它要求请求头带 `Authorization: Bearer <token>`，且 Redis 中存在 `guli:auth:<token>`
# 并反序列化出 UserInfo，否则接口一律返回 code=10011（未登录）。
# 而 guli2 的 guli-auth 当前无法启动（PasswordLoginServiceImpl 用构造器注入
# RemoteMemberService，却没有 @DubboReference，见 design.md 缺陷 2），
# 因此无法走真实登录拿 token，改为直接种入等价的 Redis 值。
#
# 值的格式：RedisUtils.getCacheObject 走 Redisson RBucket，redisson.yml 里配的是
# TypedJsonJacksonCodec + activateDefaultTyping(NON_FINAL)，所以 POJO 落库是
# Wrapper-Array 形式：["<全限定类名>",{...}]。
#
# 用法：
#   bash seed-auth-token.sh              # 种入默认 token
#   TOKEN=xxx bash seed-auth-token.sh    # 种入指定 token（需同步改 application.yml）
#
set -euo pipefail

TOKEN="${TOKEN:-mcp-live-test-token-0001}"
MEMBER_ID="${MEMBER_ID:-1}"
TTL_SECONDS="${TTL_SECONDS:-2592000}"   # 30 天

REDIS_CONTAINER="${REDIS_CONTAINER:-guli-redis}"
REDIS_PASSWORD="${REDIS_PASSWORD:-ruoyi123}"

KEY="guli:auth:${TOKEN}"
VALUE=$(cat <<JSON
["com.atlearn.guli.core.UserInfo",{"userId":${MEMBER_ID},"userKey":"${TOKEN}","isTempUser":false}]
JSON
)

docker exec "${REDIS_CONTAINER}" redis-cli -a "${REDIS_PASSWORD}" --no-auth-warning \
    SET "${KEY}" "${VALUE}" EX "${TTL_SECONDS}" > /dev/null

echo "已种入 ${KEY}（userId=${MEMBER_ID}, TTL=${TTL_SECONDS}s）"
docker exec "${REDIS_CONTAINER}" redis-cli -a "${REDIS_PASSWORD}" --no-auth-warning TTL "${KEY}"
