-- 意图节点：把新增的 cart_query MCP 工具挂到模型上
--
-- 为什么需要它：AgentToolCatalog.resolveMcpToolBindings()
-- （agent/src/main/java/com/nageoffer/ai/ragent/agent/tool/AgentToolCatalog.java:127-140）
-- 把「意图树里 mcp_tool_id 非空的节点」与「MCP 注册表里的 executor」求**交集**：
-- 只新增 executor 而不建意图节点，工具会进 unavailableToolIds 并只打一条 warn，永远不会挂给模型。
--
-- 而且模型看到的工具描述取自本表的 description（AgentToolCatalog.toBinding 用 IntentNode::getDescription
-- 拼接），不是 executor 里 Tool.builder().description(...) 那份——两处都要写好。
--
-- 列值照 guli-product-detail（2099052193645690880）逐列复制，仅改必要字段。
-- 回滚：DELETE FROM t_intent_node WHERE intent_code='guli-cart';
--
-- ⚠️ 执行完必须清意图树缓存，否则本行不生效且**不会有任何报错**：
--     DefaultIntentClassifier.loadIntentTreeData() 先读 Redis（ragent:intent:tree，TTL 7 天），
--     只有缓存为空才回落 DB。直插 SQL 绕过了后台 CRUD 的 clearIntentTreeCache()。
--
--     docker exec guli-redis redis-cli -a ruoyi123 --no-auth-warning DEL ragent:intent:tree
--
--     缓存不随 bootstrap 重启失效（重启也不清）。

INSERT INTO t_intent_node (
    id, kb_id, intent_code, name, level, parent_code, description, examples,
    collection_name, collection_names, top_k, mcp_tool_id, kind,
    prompt_snippet, prompt_template, param_prompt_template,
    sort_order, enabled, create_by, update_by, deleted
) VALUES (
    2099052215804198913, NULL, 'guli-cart', '购物车查询', 1, 'chat',
    '查询当前登录用户自己的购物车内容：已加入的商品、数量、单价、销售属性（颜色/版本等）与合计金额。不需要任何参数，身份来自当前登录用户。当用户问「我的购物车有什么」「购物车里有哪些东西」「购物车里有几件商品」时使用本工具。与商品详情查询的区别：本工具只回答当前用户自己的购物车里有什么，不查询任意 SKU 的商品信息。',
    '["我的购物车有什么","购物车里有哪些东西","看看我的购物车","我的购物车里有几件商品"]',
    NULL, '[]'::jsonb, NULL, 'cart_query', 2,
    NULL, NULL, NULL,
    30, 1, 'admin', 'admin', 0
)
ON CONFLICT (id) DO NOTHING;   -- 幂等：重复执行不报错（本文件会被反复跑，报 duplicate key 会掩盖真正的失败）
