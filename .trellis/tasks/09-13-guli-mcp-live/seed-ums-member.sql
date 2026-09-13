-- 鉴权验证用会员数据（对应 design.md 变更 7 / 本轮新增范围）
-- 背景：guli2 的 UserInfoInterceptor 强制要求 Authorization: Bearer <token>，
--       且 Redis 中必须有 guli:auth:<token> → UserInfo。该键只有真实登录才会写入，
--       而 guli-auth 的 /login 走 Dubbo RemoteMemberService，需要 ums_member 有会员。
--       ums_member 原表 0 行，故补 1 行。
-- 口令：mcp123456，哈希由 hutool 5.8.37 BCrypt.hashpw 生成（与 guli-auth 同款实现）并自验通过。
-- 注意：guli 的 status 语义为 1=正常（与 ry 的 sys_user 相反），PasswordLoginServiceImpl 判定 status != 1 即拒绝。
-- 仅插数据，不改 guli2 源码，不改表结构。
-- 回滚：DELETE FROM ums_member WHERE username='mcp_test';

USE `ry-cloud`;

DELETE FROM `ums_member` WHERE username = 'mcp_test';

INSERT INTO `ums_member` (username, password, nickname, email, status, create_time)
VALUES ('mcp_test',
        '$2a$10$pHSvgMpfoCMesJ8v/dhjMe0NWufcBFao3AfdQ0k.z6ZT.TKeb.awS',
        'MCP 验证账号',
        'mcp_test@example.com',
        1,
        NOW());
