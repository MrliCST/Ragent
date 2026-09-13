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

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具执行器：商品详情查询（真实 HTTP 调用谷粒商城接口）
 * 
 * 调用谷粒商城商品详情接口：GET /product/display/item/{skuId}
 * 返回：PmsSkuItemVo 包含 SKU 信息、图片、销售属性等
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuliProductDetailMcpExecutor {

    private static final String TOOL_ID = "product_detail_query";
    
    /**
     * 谷粒商城商品服务基础 URL
     * 配置方式：application.yml 中配置 guli.product.base-url
     * 默认值：http://localhost:8080/product
     */
    private final GuliMcpProperties guliMcpProperties;
    
    private final RestTemplateBuilder restTemplateBuilder;

    @Bean
    public McpServerFeatures.SyncToolSpecification productDetailToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(buildTool(),
                (exchange, request) -> handleCall(request));
    }

    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("skuId", Map.of(
                "type", "integer",
                "description", "SKU ID，例如：1, 2, 3 等。用于查询具体规格的商品详情"
        ));

        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of("skuId"), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询商品详细信息，包括商品名称、品牌、分类、描述、价格、图片、销售属性等。需要提供 SKU ID。")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            Long skuId = longArg(args, "skuId");

            if (skuId == null || skuId <= 0) {
                return errorResult("请提供有效的 SKU ID");
            }

            // 调用谷粒商城商品详情接口
            String url = guliMcpProperties.getProduct().getBaseUrl() + "/display/item/" + skuId;
            log.info("调用谷粒商城商品详情接口：{}", url);

            RestTemplate restTemplate = restTemplateBuilder
                    .connectTimeout(Duration.ofSeconds(5))
                    .readTimeout(Duration.ofSeconds(10))
                    .build();

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(GuliApiSupport.headers(guliMcpProperties)), Map.class);

            int status = response.getStatusCode().value();
            Map<String, Object> resultData = response.getBody();
            if (status != 200 || resultData == null) {
                return errorResult("商品详情查询失败，HTTP 状态码：" + status);
            }

            // 谷粒商城业务失败时同样返回 HTTP 200（如 skuId 不存在 → code=10002），必须再看响应体
            if (!GuliApiSupport.isSuccess(resultData)) {
                return errorResult(String.format("商品详情查询失败：%s（code=%d）",
                        GuliApiSupport.messageOf(resultData), GuliApiSupport.codeOf(resultData)));
            }

            String result = buildProductDetailResult(skuId, resultData);

            log.info("MCP 工具调用完成，toolId={}, skuId={}, elapsed={}ms",
                    TOOL_ID, skuId, System.currentTimeMillis() - startMs);
            return successResult(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败，toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return errorResult("查询失败：" + e.getMessage());
        }
    }

    /**
     * 按谷粒商城 {@code PmsSkuItemVo} 的真实结构组装文本。
     * <p>
     * 真实契约：{@code data.skuInfo.*}、{@code data.skuImages[]}、{@code data.skuItemSaleAttr[]}、
     * {@code data.spuInfoDesc}、{@code data.spuBaseAttrGroup[]}。其中 {@code skuInfo} 没有
     * brandName / categoryName / saleComment 字段，只有 brandId / catalogId / skuDesc。
     */
    @SuppressWarnings("unchecked")
    private String buildProductDetailResult(Long skuId, Map<String, Object> responseData) {
        StringBuilder sb = new StringBuilder();
        sb.append("【商品详情】\n\n");
        sb.append(String.format("SKU ID: %d\n", skuId));

        Object data = responseData.get("data");
        Object skuInfoRaw = data instanceof Map ? ((Map<String, Object>) data).get("skuInfo") : null;
        if (!(skuInfoRaw instanceof Map)) {
            sb.append("商品信息：未查询到该 SKU 的详情\n");
            return sb.toString().trim();
        }

        Map<String, Object> productData = (Map<String, Object>) data;
        Map<String, Object> skuInfo = (Map<String, Object>) skuInfoRaw;

        sb.append(String.format("商品名称：%s\n", getField(skuInfo, "skuName")));
        sb.append(String.format("商品标题：%s\n", getField(skuInfo, "skuTitle")));
        sb.append(String.format("副标题：%s\n", getField(skuInfo, "skuSubtitle")));
        sb.append(String.format("价格：￥%s\n", getField(skuInfo, "price")));
        sb.append(String.format("默认图：%s\n", getField(skuInfo, "skuDefaultImg")));
        sb.append(String.format("品牌 ID：%s\n", getField(skuInfo, "brandId")));
        sb.append(String.format("分类 ID：%s\n", getField(skuInfo, "catalogId")));
        sb.append(String.format("商品描述：%s\n", getField(skuInfo, "skuDesc")));

        appendImages(sb, productData.get("skuImages"));
        appendSaleAttrs(sb, productData.get("skuItemSaleAttr"));
        appendSpuInfoDesc(sb, productData.get("spuInfoDesc"));
        appendBaseAttrGroups(sb, productData.get("spuBaseAttrGroup"));

        sb.append("\n如需查询库存详情，请使用库存查询工具。");
        return sb.toString().trim();
    }

    private void appendImages(StringBuilder sb, Object images) {
        if (images instanceof List<?> list) {
            sb.append(String.format("商品图片：%d 张\n", list.size()));
        }
    }

    private void appendSaleAttrs(StringBuilder sb, Object attrs) {
        if (!(attrs instanceof List<?> list) || list.isEmpty()) {
            sb.append("销售属性：无\n");
            return;
        }
        sb.append(String.format("销售属性：%d 项\n", list.size()));
        for (Object item : list) {
            if (item instanceof Map<?, ?> attr) {
                sb.append(String.format("  - %s：%s\n",
                        attr.get("attrName"), joinValues(attr.get("attrValues"))));
            }
        }
    }

    private void appendSpuInfoDesc(StringBuilder sb, Object desc) {
        if (desc instanceof Map<?, ?> map) {
            Object decript = map.get("decript");
            if (decript != null && !decript.toString().isBlank()) {
                sb.append(String.format("图文介绍：%s\n", decript));
            }
        }
    }

    private void appendBaseAttrGroups(StringBuilder sb, Object groups) {
        if (!(groups instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        sb.append(String.format("规格参数：%d 组\n", list.size()));
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> group)) {
                continue;
            }
            sb.append(String.format("  - %s\n", group.get("groupName")));
            if (group.get("attrs") instanceof List<?> attrList) {
                for (Object attr : attrList) {
                    if (attr instanceof Map<?, ?> a) {
                        sb.append(String.format("      · %s：%s\n", a.get("attrName"), a.get("attrValue")));
                    }
                }
            }
        }
    }

    private String getField(Map<String, Object> data, String fieldName) {
        Object value = data.get(fieldName);
        return value != null ? value.toString() : "未知";
    }

    /**
     * 把字符串列表拼成"、"分隔的文本；非列表直接转字符串。
     */
    private static String joinValues(Object values) {
        if (values instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(list.get(i));
            }
            return sb.toString();
        }
        return values != null ? values.toString() : "未知";
    }

    private static Long longArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
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
