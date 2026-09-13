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

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.PemUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTHeader;
import cn.hutool.jwt.RegisteredPayload;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 谷粒代理令牌签发器（RS256）
 * <p>
 * 声明"我是 ragent 用户 X，正在会话 Y 里代理自己"，<b>不携带 memberId</b>——
 * 映射的权威在谷粒侧（查绑定表），这样 ragent 被攻破时最多冒充已完成绑定的会员。
 * <p>
 * 每次工具调用签一次，令牌只在一次 MCP 往返内有效，TTL 默认 60s。
 * 任何一步失败都返回 {@code null} 而非抛异常：上层据此回落到服务 token 路径，
 * 不因签发失败阻断公共数据查询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelegationTokenIssuer {

    private final DelegationProperties properties;

    /**
     * 私钥懒加载：{@code enabled=false} 或私钥缺失时保持 null，且只尝试加载一次
     */
    private volatile JWTSigner signer;
    private volatile boolean signerLoaded;

    /**
     * 签发一次工具调用用的代理令牌。
     *
     * @param ragentUserId ragent 侧用户标识（来自 sa-token）
     * @param sessionId    会话标识（conversationId），用于谷粒侧审计
     * @return JWT 字符串；未启用 / 参数缺失 / 私钥不可用 / 签名异常时返回 {@code null}
     */
    public String issue(String ragentUserId, String sessionId) {
        if (!properties.isEnabled()) {
            return null;
        }
        if (StrUtil.isBlank(ragentUserId)) {
            log.debug("代理令牌跳过签发：ragent 用户标识为空");
            return null;
        }
        JWTSigner currentSigner = signer();
        if (currentSigner == null) {
            return null;
        }

        long nowMs = System.currentTimeMillis();
        Date issuedAt = new Date(nowMs);
        Date expiresAt = new Date(nowMs + properties.getTtlSeconds() * 1000L);
        try {
            JWT jwt = JWT.create()
                    .setHeader(JWTHeader.KEY_ID, properties.getKeyId())
                    .setPayload(RegisteredPayload.ISSUER, properties.getIssuer())
                    .setPayload(RegisteredPayload.AUDIENCE, properties.getAudience())
                    .setPayload(RegisteredPayload.SUBJECT, "ragent:" + ragentUserId)
                    .setPayload(RegisteredPayload.JWT_ID, UUID.randomUUID().toString())
                    .setPayload(RegisteredPayload.ISSUED_AT, issuedAt)
                    .setPayload(RegisteredPayload.EXPIRES_AT, expiresAt)
                    .setPayload("session", sessionId)
                    .setPayload("scope", scopeList())
                    .setSigner(currentSigner);
            return jwt.sign();
        } catch (Exception e) {
            log.error("代理令牌签发失败, ragentUserId: {}, reason: {}", ragentUserId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 懒加载私钥签名器；路径为空、文件不存在或 PEM 解析失败时返回 {@code null}，不抛异常
     */
    private JWTSigner signer() {
        if (signerLoaded) {
            return signer;
        }
        synchronized (this) {
            if (signerLoaded) {
                return signer;
            }
            signerLoaded = true;
            String path = properties.getPrivateKeyPath();
            if (StrUtil.isBlank(path)) {
                log.warn("agent.delegation.enabled=true 但未配置 private-key-path，代理令牌不签发，回落服务 token");
                return null;
            }
            try (InputStream in = FileUtil.getInputStream(path)) {
                PrivateKey privateKey = PemUtil.readPemPrivateKey(in);
                signer = JWTSignerUtil.rs256(privateKey);
                log.info("代理令牌签发器就绪, keyId: {}", properties.getKeyId());
            } catch (Exception e) {
                log.error("代理令牌私钥加载失败, path: {}, reason: {}", path, e.getMessage(), e);
            }
            return signer;
        }
    }

    private List<String> scopeList() {
        String scope = properties.getScope();
        if (StrUtil.isBlank(scope)) {
            return List.of();
        }
        return Arrays.stream(scope.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .toList();
    }
}
