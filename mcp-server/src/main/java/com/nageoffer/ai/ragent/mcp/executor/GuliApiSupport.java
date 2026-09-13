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

package com.nageoffer.ai.ragent.mcp.executor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

/**
 * 调用谷粒商城 HTTP 接口的公共支撑
 * <p>
 * 两个工具共用同一套"外层信封"约定：鉴权请求头，以及 {@code {code, msg, ...}} 的响应包装
 * （详情接口的数据在 {@code data} 下，库存接口是 {@code TableDataInfo} 平铺在顶层）。
 * <p>
 * 各工具业务字段的解析留在各自的 executor 中——那是两个工具各自的知识，不在此处合并。
 */
final class GuliApiSupport {

    private GuliApiSupport() {
    }

    /**
     * 构造请求头。
     * <p>
     * 谷粒商城的 UserInfoInterceptor 会校验 {@code Authorization: Bearer <token>} 且要求
     * Redis 中存在 {@code guli:auth:<token>}，未携带时接口一律返回 {@code code=10011}。
     * 未配置 token 时不加该头，保持与改造前一致的行为。
     */
    static HttpHeaders headers(GuliMcpProperties properties) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String token = properties.getAuth().getToken();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token.trim());
        }
        return headers;
    }

    /**
     * 取响应体中的业务状态码，缺失时返回 {@code null}。
     * <p>
     * 谷粒商城即使业务失败也返回 HTTP 200，必须再看这里的 code 才能判断成功与否。
     */
    static Integer codeOf(Map<String, Object> body) {
        Object code = body.get("code");
        return code instanceof Number n ? n.intValue() : null;
    }

    /**
     * 取响应体中的业务提示信息，缺失时返回空串。
     */
    static String messageOf(Map<String, Object> body) {
        Object msg = body.get("msg");
        return msg != null ? msg.toString() : "";
    }

    /**
     * 判断业务是否成功：code 缺失时按兼容处理视为成功（老接口可能没有该字段）。
     */
    static boolean isSuccess(Map<String, Object> body) {
        Integer code = codeOf(body);
        return code == null || code == 200;
    }
}
