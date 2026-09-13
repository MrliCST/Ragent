# 谷粒商城与 Ragent MCP 集成实现文档

## 概述

本文档描述了如何通过 MCP（Model Context Protocol）将谷粒商城（guli2）业务系统与 Ragent AI 检索系统进行真实接口调用集成，实现用户通过自然语言查询商品信息和库存信息。

## 架构设计

### 三层架构

1. **第一层：guli2 侧暴露标准接口**
   - 商品详情查询：`GET /product/display/item/{skuId}`
   - 库存查询：`GET /ware/wareSku/list?skuId={skuId}&wareId={wareId}`

2. **第二层：Ragent MCP Server 侧注册 Tool**
   - `product_detail_query` - 商品详情查询工具
   - `product_stock_query` - 商品库存查询工具

3. **第三层：意图检索树**
   - 电商领域 → 商品信息 → 商品详情查询/库存查询

## 已实现组件

### 1. 配置属性类 (GuliMcpProperties.java)

```java
package com.nageoffer.ai.ragent.mcp.executor;

@ConfigurationProperties(prefix = "guli")
public class GuliMcpProperties {
    private ProductServiceConfig product;  // 商品服务配置
    private WareServiceConfig ware;        // 仓储服务配置
    private AuthConfig auth;               // 鉴权配置
}
```

**配置项：**
- `guli.product.base-url`: 商品服务基础 URL（默认：http://localhost:8080/product）
- `guli.ware.base-url`: 仓储服务基础 URL（默认：http://localhost:8080/ware）
- `guli.auth.token`: 登录态 token（可选）。**谷粒商城强制要求鉴权**，见下节

> **⚠️ 鉴权是必须的，且不由网关负责**
>
> 谷粒商城的 `guli-common/.../interceptor/UserInfoInterceptor.java` 会校验请求头
> `Authorization: Bearer <token>`，并要求 Redis 中存在 `guli:auth:<token>` 并反序列化出
> `UserInfo`；任一不满足即返回 `code=10011`（未登录）。**这个拦截器在业务服务里，直连微服务端口同样被拦，绕过网关不能绕过鉴权。**
>
> 因此 `guli.auth.token` 留空时两个工具一律拿不到数据。token 的取得方式：
> 正常情况走 `guli-auth` 登录换取；若 `guli-auth` 不可用（例如缺少 `@DubboReference` 导致无法启动），
> 可手工向 Redis 种入等价的值：
> ```
> SET guli:auth:<token> '["com.atlearn.guli.core.UserInfo",{"userId":1,"userKey":"<token>","isTempUser":false}]' EX 2592000
> ```
> 注意值是 Redisson `TypedJsonJacksonCodec` + `activateDefaultTyping(NON_FINAL)` 的 Wrapper-Array 形式
> （`["全限定类名",{...}]`），直接写普通 JSON 对象会在反序列化时失败。

### 2. 商品详情查询工具 (GuliProductDetailMcpExecutor.java)

**Tool ID:** `product_detail_query`

**功能：** 真实调用谷粒商城商品详情接口

**输入参数：**
```json
{
  "skuId": "integer (required) - SKU ID，例如：1, 2, 3"
}
```

**调用接口：** `GET {guli.product.base-url}/display/item/{skuId}`

**返回数据：** 格式化后的商品详情信息，包括：
- SKU ID、商品名称、商品标题、副标题
- 价格、默认图、商品描述
- 品牌 ID、分类 ID（**注意：接口只返回 ID，不返回品牌名/分类名**——`PmsSkuItemVo` 没有 `brandName` / `categoryName` 字段）
- 图片张数、销售属性（属性名 + 可选值列表）
- 图文介绍、规格参数（分组名 + 属性名值对）

**真实响应契约**（`R<PmsSkuItemVo>`，数据在 `data` 下）：
```
data.skuInfo.{skuId,spuId,skuName,skuTitle,skuSubtitle,price,skuDefaultImg,brandId,catalogId,skuDesc}
data.skuImages[]          图片列表
data.skuItemSaleAttr[].{attrName,attrValues[]}
data.spuInfoDesc.decript  图文介绍（注意拼写就是 decript）
data.spuBaseAttrGroup[].{groupName,attrs[].{attrName,attrValue}}
```
> ⚠️ 业务失败时该接口**同样返回 HTTP 200**，错误在响应体的 `code`/`msg` 里
> （如 skuId 不存在 → `{"code":10002,"msg":"异步读取sku信息失败"}`）。
> 判成功必须看 `code`，不能只看 HTTP 状态码。

### 3. 商品库存查询工具 (GuliProductStockMcpExecutor.java)

**Tool ID:** `product_stock_query`

**功能：** 真实调用谷粒商城库存查询接口

**输入参数：**
```json
{
  "skuId": "integer (required) - SKU ID",
  "wareId": "integer (optional) - 仓库 ID，不提供则查询所有仓库"
}
```

**调用接口：** `GET {guli.ware.base-url}/wareSku/list?skuId={skuId}&wareId={wareId}`

**返回数据：** 格式化后的库存信息，包括：
- 各仓库的总库存、锁定库存、可用库存
- 库存汇总
- 库存状态提示（缺货/紧张/较少/充足）

**真实响应契约**（`TableDataInfo<WmsWareSkuVo>`，**字段平铺在顶层，没有 `data` 包裹**）：
```
{"total":2,"rows":[{"id":1,"skuId":1,"wareId":1,"stock":120,"skuName":"...","stockLocked":5},...],
 "code":200,"msg":"查询成功"}
```
> - `rows` 在**顶层**，不是 `data.rows`。
> - 行内**没有 `wareName`**，只有 `wareId`；要显示仓库名需另查 `wms_ware_info`。
> - skuId 不存在时返回 `rows:[]` + `code:200`——这是**正常的空结果**，不是错误，工具应返回空提示而非 `isError`。

### 4. MCP Server 配置 (McpServerConfig.java)

自动注册所有 `McpServerFeatures.SyncToolSpecification` Bean 到 MCP Server。

## 配置文件

### application.yml

```yaml
server:
  port: 9099

spring:
  application:
    name: ragent-mcp-server

# 谷粒商城服务配置
guli:
  product:
    base-url: http://localhost:8080/product
  ware:
    base-url: http://localhost:8080/ware
  auth:
    token: <登录态 token>
```

> 若不经网关直连微服务端口（`guli-product` :9214、`guli-ware` :9215），base-url 写
> `http://localhost:9214` / `http://localhost:9215` 即可——注意**不要**再带 `/product`、`/ware`
> 前缀，那是网关 `StripPrefix=1` 剥掉的部分。

## 部署步骤

### 前置条件

1. **谷粒商城服务启动**
   - `guli-product` 服务运行在 `http://localhost:8080/product`
   - `guli-ware` 服务运行在 `http://localhost:8080/ware`

2. **验证接口可访问**（必须带 `Authorization` 头，否则返回 `code=10011`）
   ```bash
   # 测试商品详情接口
   curl -H "Authorization: Bearer <token>" http://localhost:8080/product/display/item/1

   # 测试库存查询接口
   curl -H "Authorization: Bearer <token>" http://localhost:8080/ware/wareSku/list?skuId=1
   ```
   > 返回 HTTP 200 不等于成功，还要看响应体里的 `code` 是否为 200。

### 启动 MCP Server

```bash
cd /workspace/mcp-server
mvn clean package -DskipTests
java -jar target/ragent-mcp-server-0.0.1-SNAPSHOT.jar
```

或指定外部配置：

```bash
java -jar target/ragent-mcp-server-0.0.1-SNAPSHOT.jar \
  --guli.product.base-url=http://guli-product:8080/product \
  --guli.ware.base-url=http://guli-ware:8080/ware
```

### 验证 MCP Server

MCP Server 启动后，会在端口 9099 提供 HTTP 端点：
- MCP 协议端点：`http://localhost:9099/mcp`

## 使用示例

### 场景 1：查询商品详情

**用户问题：** "帮我查一下 SKU 为 1 的商品详情"

**Agent 处理流程：**
1. 意图识别：命中"商品详情查询"意图
2. 参数提取：从问题中提取 `skuId=1`
3. 工具调用：调用 `product_detail_query` 工具
4. 接口请求：`GET http://localhost:8080/product/display/item/1`
5. 结果返回：格式化商品详情信息

### 场景 2：查询商品库存

**用户问题：** "SKU 1001 现在还有货吗？"

**Agent 处理流程：**
1. 意图识别：命中"库存查询"意图
2. 参数提取：从问题中提取 `skuId=1001`
3. 工具调用：调用 `product_stock_query` 工具
4. 接口请求：`GET http://localhost:8080/ware/wareSku/list?skuId=1001`
5. 结果返回：格式化库存信息 + 库存状态提示

### 场景 3：查询指定仓库库存

**用户问题：** "查一下 SKU 1 在仓库 2 的库存"

**Agent 处理流程：**
1. 意图识别：命中"库存查询"意图
2. 参数提取：`skuId=1`, `wareId=2`
3. 工具调用：调用 `product_stock_query` 工具
4. 接口请求：`GET http://localhost:8080/ware/wareSku/list?skuId=1&wareId=2`
5. 结果返回：指定仓库的库存详情

## 意图检索树配置

在 Ragent 的 `IntentTreeFactory.java` 中配置意图节点：

```java
// 电商领域 - 商品信息分类
IntentNode productInfoNode = new IntentNode("商品信息");

// 商品详情查询意图
IntentNode productDetailIntent = new IntentNode("商品详情查询");
productDetailIntent.setMcpToolId("product_detail_query");
productDetailIntent.setParameterExtractPrompt(MCP_PRODUCT_DETAIL_PARAMETER_EXTRACT_PROMPT);
productDetailIntent.setResultTemplate(MCP_PRODUCT_DETAIL_PROMPT_TEMPLATE);

// 库存查询意图
IntentNode stockQueryIntent = new IntentNode("库存查询");
stockQueryIntent.setMcpToolId("product_stock_query");
stockQueryIntent.setParameterExtractPrompt(MCP_PRODUCT_STOCK_PARAMETER_EXTRACT_PROMPT);
stockQueryIntent.setResultTemplate(MCP_PRODUCT_STOCK_PROMPT_TEMPLATE);

productInfoNode.addChild(productDetailIntent);
productInfoNode.addChild(stockQueryIntent);
```

## 错误处理

### 常见错误及解决方案

| 错误现象 | 可能原因 | 解决方案 |
|---------|---------|---------|
| 连接超时 | 谷粒商城服务未启动 | 检查服务状态，确保端口可访问 |
| 404 Not Found | 接口路径错误或 SKU 不存在 | 验证接口路径和参数 |
| 500 Internal Error | 谷粒商城内部错误 | 查看 guli 服务日志 |
| 参数解析失败 | 参数格式不正确 | 确保 skuId 为正整数 |

## 扩展指南

### 添加新的 Tool

1. 创建新的 Executor 类，继承 MCP Tool 规范
2. 定义 Tool ID、描述、输入 Schema
3. 实现 `handleCall` 方法进行真实 HTTP 调用
4. 使用 `@Bean` 注册 `SyncToolSpecification`

### 支持更多业务场景

可扩展的 Tool 列表：
- `order_query` - 订单状态查询
- `logistics_query` - 物流信息查询
- `price_query` - 价格查询
- `promotion_query` - 促销活动查询

## 关键设计原则

1. **真实调用**：所有 Tool 都通过 HTTP 真实调用谷粒商城接口，不使用模拟数据
2. **配置驱动**：通过 `application.yml` 配置服务地址，支持环境切换
3. **错误友好**：提供清晰的错误提示和状态反馈
4. **易于扩展**：遵循统一的 Tool 实现模式，便于添加新业务 Tool

## 项目结构

```
mcp-server/
├── src/main/java/com/nageoffer/ai/ragent/mcp/
│   ├── McpServerApplication.java          # 启动类
│   ├── config/
│   │   └── McpServerConfig.java           # MCP Server 配置
│   └── executor/
│       ├── GuliMcpProperties.java         # 配置属性类
│       ├── GuliProductDetailMcpExecutor.java  # 商品详情工具
│       └── GuliProductStockMcpExecutor.java   # 库存查询工具
└── src/main/resources/
    └── application.yml                    # 配置文件
```

## 参考链接

- 谷粒商城 GitHub: https://github.com/MrliCST/guli2
- MCP 协议规范：https://modelcontextprotocol.io/
