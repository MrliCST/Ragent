/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.config;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;

/**
 * 代理令牌的跨系统契约常量与取值工具。
 * <p>
 * mcp-server 是独立进程（不依赖 rag 模块），因此这里的常量与
 * {@code com.nageoffer.ai.ragent.rag.core.mcp.DelegationContext} 成对声明；
 * 契约见 {@code .trellis/tasks/09-13-agent-delegation-token/design.md}。
 * <p>
 * 本类**只透传、不校验**：mcp-server 没有谷粒公钥，也不该有——验签与身份还原
 * 的唯一权威在谷粒侧（design.md「校验位置」）。
 */
public final class AgentDelegation {

    /**
     * 传输层请求头名（ragent → mcp-server）
     */
    public static final String HEADER = "X-Agent-Delegation";

    /**
     * 传输上下文的键，与 {@link #HEADER} 同值
     */
    public static final String CONTEXT_KEY = HEADER;

    private AgentDelegation() {
    }

    /**
     * 从工具 handler 的 exchange 中取出本次调用携带的代理令牌，无则返回 {@code null}
     */
    public static String tokenOf(McpSyncServerExchange exchange) {
        if (exchange == null) {
            return null;
        }
        McpTransportContext context = exchange.transportContext();
        if (context == null) {
            return null;
        }
        Object token = context.get(CONTEXT_KEY);
        return token != null ? token.toString() : null;
    }

}
