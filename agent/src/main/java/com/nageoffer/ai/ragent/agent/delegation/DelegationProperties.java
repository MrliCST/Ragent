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

package com.nageoffer.ai.ragent.agent.delegation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 谷粒代理令牌签发配置（agent.delegation 段）
 * <p>
 * 默认关闭：{@code enabled=false} 时 {@link DelegationTokenIssuer} 不签发令牌，
 * MCP 调用回落服务 token 路径（公共数据查询能力不退化）。
 * <p>
 * 私钥走文件路径 + 环境变量覆盖，不入库、不入仓；公私钥算法为 RS256，
 * 谷粒侧只持有公钥，因而不具备签发能力。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.delegation")
public class DelegationProperties {

    /**
     * 总开关，默认关闭
     */
    private boolean enabled = false;

    /**
     * 私钥 PEM 文件路径（PKCS#8），不随仓库分发
     */
    private String privateKeyPath;

    /**
     * 密钥标识，写入 JWT header 的 kid，供验签方选择公钥；轮换时换新 kid
     */
    private String keyId = "ragent-2026-09";

    /**
     * 签发方（iss），验签方必须校验
     */
    private String issuer = "ragent";

    /**
     * 受众（aud），验签方必须校验，防止其它系统的令牌被拿来调谷粒
     */
    private String audience = "guli-mall";

    /**
     * 令牌有效期（秒），贴近单次工具调用预算（实测 MCP 往返为毫秒级）
     */
    private long ttlSeconds = 60;

    /**
     * 代理令牌的授权范围（scope），本次只做购物车读
     */
    private String scope = "cart:read";
}
