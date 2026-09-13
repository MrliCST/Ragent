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

package com.nageoffer.ai.ragent.rag.core.mcp;

import io.modelcontextprotocol.common.McpTransportContext;

import java.util.Map;

/**
 * 单次 MCP 调用携带的代理令牌载体（ThreadLocal 桥接）
 * <p>
 * MCP 客户端的 {@code transportContextProvider} 是个 {@code Supplier}，SDK 无法把
 * "当前是哪次调用"喂给它，只能从调用线程上读取。因此这里的 ThreadLocal 是<b>刻意的桥接</b>，
 * 不是偷懒：
 * <ul>
 *     <li>{@code McpSyncClient.callTool} 是同步阻塞调用，SDK 在<b>当前线程</b>
 *     {@code withProvidedContext(...).block()}，supplier 的求值线程就是 {@code set} 的线程；</li>
 *     <li>不要"优化"成 Reactor Context（会改变求值线程）或全局变量（会串号）。</li>
 * </ul>
 * 令牌只能走传输层 header，<b>绝不进工具参数</b>——工具参数是模型生成的 JSON，不是可信信道。
 */
public final class DelegationContext {

    /**
     * 传输层请求头名（ragent → mcp-server 的跨系统契约）
     */
    public static final String HEADER = "X-Agent-Delegation";

    /**
     * {@link McpTransportContext} 中的键，与 {@link #HEADER} 同值
     */
    public static final String KEY = HEADER;

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private DelegationContext() {
    }

    /**
     * 绑定本次调用线程的代理令牌；{@code null} 视为清除
     */
    public static void set(String token) {
        if (token == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(token);
        }
    }

    /**
     * 读取当前线程的代理令牌，无则返回 {@code null}
     */
    public static String get() {
        return HOLDER.get();
    }

    /**
     * 清除当前线程的代理令牌，必须在 {@code finally} 中调用，避免线程复用导致串号
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 供 SDK 的 {@code transportContextProvider} 求值：把当前线程的令牌包成一次调用的传输上下文。
     * 无令牌时返回 {@link McpTransportContext#EMPTY}（{@code create(Map)} 不接受 null 值）。
     */
    public static McpTransportContext snapshot() {
        String token = HOLDER.get();
        if (token == null) {
            return McpTransportContext.EMPTY;
        }
        return McpTransportContext.create(Map.of(KEY, token));
    }
}
