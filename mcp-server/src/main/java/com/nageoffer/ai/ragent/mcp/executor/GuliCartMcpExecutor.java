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

import com.nageoffer.ai.ragent.mcp.config.AgentDelegation;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具执行器：当前用户购物车查询（真实 HTTP 调用谷粒商城接口）
 * <p>
 * 调用谷粒商城购物车接口：GET /cart（guli-shopcart 服务，直连 :9216）
 * 返回：{@code R<ShopCart>}，{@code data} 为 {@code {items[], countNum, countType, totalAmount, reduce}}。
 * <p>
 * <b>本工具不接受任何参数，也不含任何身份参数。</b>购物车是用户维度数据，调用身份完全来自
 * MCP 传输层携带的代理令牌（{@code X-Agent-Delegation}），由
 * {@link AgentDelegation#tokenOf(McpSyncServerExchange)} 取出后经
 * {@link GuliApiSupport#headers(GuliMcpProperties, String)} 转发为谷粒的
 * {@code Authorization} 头。工具参数是模型生成的 JSON，模型会看到并可被 prompt injection 改写，
 * 因此身份绝不走那里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuliCartMcpExecutor {

    private static final String TOOL_ID = "cart_query";

    /**
     * 谷粒商城购物车服务基础 URL
     * 配置方式：application.yml 中配置 guli.shopcart.base-url
     * 默认值：http://localhost:8080/shopcart
     */
    private final GuliMcpProperties guliMcpProperties;

    private final RestTemplateBuilder restTemplateBuilder;

    @Bean
    public McpServerFeatures.SyncToolSpecification cartToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(buildTool(),
                (exchange, request) -> handleCall(exchange, request));
    }

    private Tool buildTool() {
        JsonSchema inputSchema = new JsonSchema(
                "object", Map.of(), List.of(), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询当前用户自己的购物车内容，包括已加入的商品、数量、单价、销售属性（颜色/版本等）"
                        + "与合计金额。调用身份来自当前登录用户，不需要也不接受任何参数。"
                        + "当用户问「我的购物车有什么」「购物车里有哪些东西」时使用本工具。")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(McpSyncServerExchange exchange, CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            // 购物车是用户维度接口，无任何工具参数；身份只经传输层令牌传递
            String url = guliMcpProperties.getShopcart().getBaseUrl() + "/cart";
            log.info("调用谷粒商城购物车接口：{}", url);

            RestTemplate restTemplate = restTemplateBuilder
                    .connectTimeout(Duration.ofSeconds(5))
                    .readTimeout(Duration.ofSeconds(10))
                    .build();

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(GuliApiSupport.headers(guliMcpProperties, AgentDelegation.tokenOf(exchange))), Map.class);

            int status = response.getStatusCode().value();
            Map<String, Object> resultData = response.getBody();
            if (status != 200 || resultData == null) {
                return errorResult("购物车查询失败，HTTP 状态码：" + status);
            }

            // 谷粒商城业务失败时同样返回 HTTP 200（如未登录 → code=10011），必须再看响应体
            if (!GuliApiSupport.isSuccess(resultData)) {
                return errorResult(String.format("购物车查询失败：%s（code=%d）",
                        GuliApiSupport.messageOf(resultData), GuliApiSupport.codeOf(resultData)));
            }

            String result = buildCartResult(resultData);

            log.info("MCP 工具调用完成，toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs);
            return successResult(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败，toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return errorResult("查询失败：" + e.getMessage());
        }
    }

    /**
     * 按谷粒商城 {@code R<ShopCart>} 的真实结构组装文本。
     * <p>
     * 真实契约：{@code data.items[]} 每项含 {@code skuId} / {@code title} / {@code defaultImage} /
     * {@code price} / {@code count} / {@code saleAttr[]}（每项 {@code attrName}+{@code attrValue}）/
     * {@code hasStock} / {@code totalPrice}；{@code data} 另有 {@code countNum}（商品总件数）/
     * {@code countType}（商品类型数）/ {@code totalAmount} / {@code reduce}。
     * <p>
     * <b>购物车为空是正常结果</b>（谷粒返回 {@code items: []} 且各项为 0），这里渲染成明确的
     * "购物车是空的"文案，而不是 {@code isError=true} —— 空车与非空车是两种要被区分开的正常结果。
     */
    @SuppressWarnings("unchecked")
    private String buildCartResult(Map<String, Object> responseData) {
        StringBuilder sb = new StringBuilder();
        sb.append("【我的购物车】\n\n");

        Object data = responseData.get("data");
        if (!(data instanceof Map)) {
            sb.append("未查询到购物车数据\n");
            return sb.toString().trim();
        }

        Map<String, Object> cart = (Map<String, Object>) data;
        Object itemsRaw = cart.get("items");
        if (!(itemsRaw instanceof List<?> items) || items.isEmpty()) {
            sb.append("购物车是空的，当前没有任何商品。\n");
            sb.append("商品件数：0\n");
            sb.append("商品类型数：0\n");
            sb.append("商品总金额：￥0\n");
            return sb.toString().trim();
        }

        sb.append(String.format("商品类型数：%s\n", getField(cart, "countType")));
        sb.append(String.format("商品总件数：%s\n", getField(cart, "countNum")));
        sb.append(String.format("商品总金额：￥%s\n", getField(cart, "totalAmount")));
        sb.append(String.format("优惠减免：￥%s\n", getField(cart, "reduce")));
        sb.append(String.format("\n共 %d 种商品：\n", items.size()));

        int index = 1;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> cartItem = (Map<String, Object>) raw;
            sb.append("\n---\n");
            sb.append(String.format("%d. %s\n", index++, getField(cartItem, "title")));
            sb.append(String.format("   SKU ID：%s\n", getField(cartItem, "skuId")));
            sb.append(String.format("   单价：￥%s\n", getField(cartItem, "price")));
            sb.append(String.format("   数量：%s\n", getField(cartItem, "count")));
            sb.append(String.format("   小计：￥%s\n", totalPriceOf(cartItem)));
            sb.append(String.format("   销售属性：%s\n", saleAttrText(cartItem.get("saleAttr"))));
            Boolean hasStock = booleanField(cartItem, "hasStock");
            if (hasStock != null) {
                sb.append(String.format("   有货：%s\n", hasStock ? "是" : "否"));
            }
        }

        return sb.toString().trim();
    }

    /**
     * 小计优先取服务端返回的 {@code totalPrice}（= 单价 × 数量），缺失时按单价 × 数量现算。
     */
    private String totalPriceOf(Map<String, Object> cartItem) {
        Object total = cartItem.get("totalPrice");
        if (total != null) {
            return total.toString();
        }
        Object price = cartItem.get("price");
        Object count = cartItem.get("count");
        if (price != null && count != null) {
            try {
                return new BigDecimal(price.toString())
                        .multiply(new BigDecimal(count.toString()))
                        .toPlainString();
            } catch (NumberFormatException e) {
                return "未知";
            }
        }
        return "未知";
    }

    /**
     * 把销售属性渲染成"颜色：白色、版本：8GB+128GB"形式；无属性时返回"无"。
     */
    private String saleAttrText(Object saleAttr) {
        if (!(saleAttr instanceof List<?> list) || list.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> attr)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(String.format("%s：%s", attr.get("attrName"), attr.get("attrValue")));
        }
        return sb.length() > 0 ? sb.toString() : "无";
    }

    private String getField(Map<String, Object> data, String fieldName) {
        Object value = data.get(fieldName);
        return value != null ? value.toString() : "未知";
    }

    private static Boolean booleanField(Map<String, Object> data, String fieldName) {
        Object value = data.get(fieldName);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    private static CallToolResult successResult(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }
}
