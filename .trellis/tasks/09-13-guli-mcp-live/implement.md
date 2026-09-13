# 执行计划：打通谷粒商城两个 MCP 工具的真实调用链路

## 执行顺序总览

```
S0 前置校验
 └ S1 修 guli-mysql        ← 阻塞 S2（nacos depends_on mysql healthy）
    └ S2 建 guli-nacos + 确认 dev namespace 配置
       └ S3 导入业务表 + seed 库存数据
          └ S4 起 guli-product(9214)   ← 实际卡在 SkuEsMapper bean 缺失，非 ES
             └ S5 起 guli-ware(9215)   ← 需补 datasource 配置
                └ S6 直连冒烟（不经 MCP，先把两个接口打通）
                   └ S6.5 种入 auth token + 修 /display/item 上游缺陷 【执行中新增】
                      └ S7 改 executor 解析 + MCP 配置
                         └ S8 重建 mcp-server 并端到端验证
                            └ S9 收尾取证
```

S6 是**关键检查点**：先把两个接口用 curl 打通，再动 executor 代码。这样若 S7 之后失败，能立刻定位是解析问题还是链路问题。**该检查点在本任务中确实起到了设计作用**——两个 guli2 侧阻塞（鉴权 10011、详情 10002）都是在 S6 暴露的，若先改 executor 就会被误判成解析 bug。

> **执行状态：全部完成。** 下方每步的"实测"块记录实际结果与对计划的偏离；计划中被证伪的判断保留原文并标注，便于回溯。

---

## S0 前置校验

- [ ] 确认 9214 / 9215 / 9099 未被占用
  ```bash
  for p in 9214 9215 9099; do printf "%s " $p; ss -lnt | grep -q ":$p " && echo BUSY || echo FREE; done
  ```
- [ ] 确认 guli2 jar 存在且未过期
  ```bash
  ls -la /home/lyz/code/guli2/ruoyi-cloud-guli-mail/guli-mall/guli-{product,ware}/target/guli-{product,ware}.jar
  ```
- [ ] 确认 nacos 镜像存在
  ```bash
  docker images | grep ruoyi/ruoyi-nacos
  ```

## S1 修复并启动 guli-mysql

- [ ] 删除残留软链接（**唯一需要删的东西，先确认它是软链接**）
  ```bash
  F=/home/lyz/code/guli2/ruoyi-cloud-guli-mail/data-home/mnt/mysql/data/mysql.sock
  ls -la "$F"        # 期望：lrwxrwxrwx ... -> /var/run/mysqld/mysqld.sock
  rm -f "$F"
  ```
- [ ] 启动容器
  ```bash
  docker start guli-mysql
  ```
- [ ] 校验（healthcheck interval 5s × retries 30，最多等 ~150s）
  ```bash
  docker inspect --format '{{.State.Health.Status}}' guli-mysql   # 期望 healthy
  ss -lnt | grep ':3306'                                          # 期望 LISTEN
  docker exec guli-mysql mysql -uroot -ppassword -e "SHOW DATABASES;"
  ```
- [ ] 失败则看日志：`docker logs --tail 50 guli-mysql`

## S2 创建 guli-nacos 并确认配置

- [ ] 启动
  ```bash
  cd /home/lyz/code/guli2/ruoyi-cloud-guli-mail/script
  docker compose -f docker-compose-infra.yml up -d nacos
  ```
- [ ] 校验
  ```bash
  docker inspect --format '{{.State.Health.Status}}' guli-nacos    # 期望 healthy
  curl -s http://localhost:8848/nacos/v1/console/health/readiness
  ```
- [ ] 确认 `dev` namespace 与配置是否已存在（`ry-config.sql` 是否随数据卷初始化过）
  ```bash
  docker exec guli-mysql mysql -uroot -ppassword -e "USE \`ry-config\`; SELECT tenant_id,data_id,group_id FROM config_info;" | head -30
  ```
- [ ] 若无 `dev` 配置 → 选一条路径补齐：
  - 路径 A（推荐）：导入 `config/base-sql-config/ry-config.sql`
  - 路径 B：经 Nacos OpenAPI 逐个发布 `config/local-config/nacos/*.yml` 到 namespace `dev` / group `DEFAULT_GROUP`
- [ ] 校验配置可读
  ```bash
  curl -s "http://localhost:8848/nacos/v1/cs/configs?dataId=datasource.yml&group=DEFAULT_GROUP&tenant=dev"
  curl -s "http://localhost:8848/nacos/v1/cs/configs?dataId=guli-product.yml&group=DEFAULT_GROUP&tenant=dev"
  ```

## S3 导入业务表与库存 seed

- [ ] 导入商品表（文件内含 `use \`ry-cloud\`;`）
  ```bash
  docker exec -i guli-mysql mysql -uroot -ppassword < \
    /home/lyz/code/guli2/ruoyi-cloud-guli-mail/config/business-sql-config/gulimall_pms.sql
  ```
- [ ] 导入仓储表结构与 seed
  ```bash
  docker exec -i guli-mysql mysql -uroot -ppassword < \
    /home/lyz/code/guli2/ruoyi-cloud-guli-mail/config/business-sql-config/gulimall_wms.sql
  ```
- [ ] 新增并导入 seed（3-4 条，sku_id ∈ {1,2,3}，见 design.md 变更 4）
- [ ] 校验（对应 AC1）
  ```bash
  docker exec guli-mysql mysql -uroot -ppassword -e "
    USE \`ry-cloud\`;
    SELECT COUNT(*) AS sku_cnt FROM pms_sku_info;          -- 期望 26
    SELECT sku_id,ware_id,stock,stock_locked FROM wms_ware_sku;  -- 期望 3-4 行
  "
  ```
- [ ] **回滚点**：`DELETE FROM wms_ware_sku WHERE sku_id IN (1,2,3);`

## S4 启动 guli-product（R1 判定树）

- [ ] 原样启动
  ```bash
  cd /home/lyz/code/guli2/ruoyi-cloud-guli-mail/guli-mall/guli-product
  nohup java -jar target/guli-product.jar > /tmp/guli-product.log 2>&1 &
  ```
- [ ] 等待并判定
  ```bash
  until grep -qE "Started .*Application|APPLICATION FAILED TO START|Error starting" /tmp/guli-product.log; do sleep 2; done
  tail -40 /tmp/guli-product.log
  ```
- [ ] 若成功 → 跳过以下两条
- [ ] ~~若失败于 ES → 尝试 1（不新增服务）~~ 【已证伪，未触发】
- [ ] ~~若尝试 1 仍失败 → 停止并回报~~ 【已证伪，未触发】

> **实测（偏离计划）**：启动失败与 ES **无关**。真实报错是 `BaseEsMapper` bean 缺失——`esmapper/SkuEsMapper.java` 在提交 `9063a9a` 中被删除。计划里那条"ES 判定树"从未触发，ES 容器始终未启动。
>
> 实际处置（未改源码，未起 ES）：
> ```bash
> cd /home/lyz/code/guli2/ruoyi-cloud-guli-mail/guli-mall/guli-product
> nohup java -jar target/guli-product.jar --spring.main.lazy-initialization=true > /tmp/guli-product.log 2>&1 &
> ```
> 惰性初始化使缺失的 ES mapper bean 在启动期不被实例化；`/display/item` 本身只走 MyBatis mapper，运行期不触碰 ES，故功能不受影响。

- [x] 校验注册成功（对应 AC2）——**实测通过**，`dev` namespace 下 1 个实例 `10.148.169.152:9214`，`healthy=true`（Nacos 需带 `accessToken`，见下方 S2/S6 注）

## S5 启动 guli-ware

- [x] 同 S4 方式启动 `guli-ware.jar`，日志 `/tmp/guli-ware.log`

> **实测（偏离计划）**：首启失败于 `dynamic-datasource can not find primary datasource`——Nacos `dev` namespace 下**没有** `guli-ware.yml`（配置缺失，非代码问题）。用外部配置文件补齐，**未写入 Nacos**（避免污染共享配置）：
> ```bash
> cat > /tmp/guli-ware-datasource.yml <<'YML'
> spring:
>   datasource:
>     dynamic:
>       primary: master
>       datasource:
>         master:
>           driver-class-name: com.mysql.cj.jdbc.Driver
>           url: jdbc:mysql://guli-mysql:3306/ry-cloud?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true
>           username: root
>           password: password
> YML
> nohup java -jar guli-mall/guli-ware/target/guli-ware.jar \
>   --spring.config.additional-location=file:/tmp/guli-ware-datasource.yml > /tmp/guli-ware.log 2>&1 &
> ```
> Dubbo 注册未阻塞（计划中的 R3 未发生）。启动耗时 21.87s。
> 同一份配置也被 `guli-member`/`guli-auth` 复用（`/tmp/guli-member-datasource.yml`、`/tmp/guli-auth-datasource.yml`）。

- [x] 校验 Nacos 中 `guli-ware` 实例存在 —— **实测通过**：`10.148.169.152:9215`，`healthy=true`

## S6 直连冒烟（关键检查点，先于改代码）

- [x] 商品详情（**skuId 用真实主键 1**）——**首轮未通过，暴露两个 guli2 阻塞，见下**
- [x] 库存（seed 后）——同首轮受阻

> **实测（首轮：两个接口都返回 `code=10011 未登录，请先登录`）**
>
> 根因不在网关，而在 `guli-common/.../interceptor/UserInfoInterceptor.java`：
> ```java
> String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
> if (authHeader == null || !authHeader.startsWith("Bearer ")) throw new BusinessException(UNAUTHORIZED);
> String token = authHeader.substring("Bearer ".length());
> UserInfo userInfo = RedisUtils.getCacheObject("guli:auth:" + token);   // 取不到即 401
> ```
> **直连微服务同样被拦**，计划中"绕过网关即无需 token"的判断在此被证伪。
>
> 计划原定走 guli-auth 真实登录，实测**走不通**：`guli-auth` 启动失败于
> `No qualifying bean of type 'com.atlearn.guli.RemoteMemberService'`——`guli-auth` 全模块
> **0 个 `@DubboReference`**，而 `PasswordLoginServiceImpl:39` / `EmailLoginServiceImpl:36`
> 却按普通构造器注入。这是 guli2 master 既有缺陷（正确写法可参考 `PmsSpuServiceImpl:72-77`）。
> 经用户确认，改为手工向 Redis 种入与真实登录等价的 `UserInfo`：
> ```bash
> bash .trellis/tasks/09-13-guli-mcp-live/seed-auth-token.sh
> ```
> Redisson 用 `TypedJsonJacksonCodec` + `activateDefaultTyping(NON_FINAL)`，故值为 Wrapper-Array 形式：
> `["com.atlearn.guli.core.UserInfo",{"userId":1,"userKey":"mcp-live-test-token-0001","isTempUser":false}]`
>
> 种入后两个接口均返回 `code=200` → 对应 AC10。

### S6.5 修复阻断详情工具的上游缺陷【执行中新增】

- [x] 定位：`PmsDisplayServiceImpl:306` 在 MyBatis-Plus wrapper 中使用了 lambda 表达式
  ```java
  // 错误：MP 的 lambdaQuery().eq() 不接受 Lambda 函数式接口，运行期抛异常
  Wrappers.<PmsSkuImages>lambdaQuery().eq(x -> x.getSkuId(), skuId));
  // 正确：方法引用
  Wrappers.<PmsSkuImages>lambdaQuery().eq(PmsSkuImages::getSkuId, skuId));
  ```
  该异常发生在 `CompletableFuture` 内 → `allOf(...).join()` 抛 `CompletionException`
  → `BusinessException(ASYNC_READ_SKUITEM_FAILED=10002)`。**`/display/item` 恒失败，与 skuId 取值无关。**
- [x] 用户批准后改这 1 行（本任务**唯一**一次改 guli2 源码）
- [x] 先停 guli-product 再重建（避免 Maven 覆盖 fat jar 时运行中的 JVM 惰性读取到半成品）
  ```bash
  cd /home/lyz/code/guli2/ruoyi-cloud-guli-mail
  mvn -pl guli-mall/guli-product -am package -DskipTests -Dmaven.test.skip=true   # MVN_EXIT=0
  nohup java -jar target/guli-product.jar --spring.main.lazy-initialization=true > /tmp/guli-product.log 2>&1 &
  ```
  - 坑 1：在模块目录内直接 `mvn package` 会因 `org.dromara:ruoyi-common:pom:${revision}` 无法解析而失败，**必须在仓库根目录带 `-am` 构建**。
  - 坑 2：管道后接 `echo "EXIT=$?"` 取到的是 `tail` 的退出码，会掩盖 Maven 的真实失败；应取 `PIPESTATUS[0]`，或核对 jar 时间戳。

- [x] 库存带 wareId 过滤
  ```bash
  curl -s "http://localhost:9215/wareSku/list?skuId=1&wareId=2" | python3 -m json.tool
  ```
- [x] 错误路径（契约 C）
  ```bash
  curl -s http://localhost:9214/display/item/999999 | python3 -m json.tool
  ```
  实测：`{"code":10002,"msg":"异步读取sku信息失败"}`——注意 skuId 不存在时
  `baseMapper.selectById()` 返回 null，随后 `skuInfo.getSpuId()` NPE，被同一个
  `catch (CompletionException)` 兜住，因此**与 S6.5 那个 bug 报同一个错误码**，但根因不同。

**S6 全部通过后才进入 S7。任何一条不通，先修链路，不要改 executor。** —— 本次严格执行：两个阻塞都是在 S6 修完才动 executor 的。

## S7 修正 executor 解析与 MCP 配置

- [x] 按 design.md 变更 5 改 `GuliProductDetailMcpExecutor.buildProductDetailResult`
- [x] 按 design.md 变更 5 改 `GuliProductStockMcpExecutor.buildStockResult`
- [x] 两者补 `code != 200` 判定 → `isError=true` 且带 `msg`
- [x] 确认工具 `name`/`description`/`inputSchema`/参数校验**未变**
- [x] 按 design.md 变更 6 改 `mcp-server/src/main/resources/application.yml` 的 base-url

> **实测（执行中新增两处）**：
> 1. **新增 `GuliApiSupport.java`**（包内可见的 final 工具类）：抽出两个 executor 共用的
>    "外层信封"逻辑——`headers()`（含 Bearer token）、`codeOf()`、`messageOf()`、`isSuccess()`。
>    两个工具的业务字段解析仍留在各自 executor，未强行合并。
> 2. **`GuliMcpProperties` 新增 `auth.token`**，并改用 `restTemplate.exchange(..., new HttpEntity<>(headers), ...)`
>    以携带请求头——原先的 `getForEntity` 无法带 `Authorization`。
> 3. `application.yml` 除 base-url 外还需补 `guli.auth.token`，否则接口返回 10011。

- [x] 编译 —— **实测通过**
  ```bash
  cd /home/lyz/code/Ragent
  mvn -q -pl mcp-server -am package -DskipTests -Dmaven.test.skip=true   # MVN_EXIT=0
  ```

## S8 重建 mcp-server 并端到端验证

- [x] 启动
  ```bash
  cd /home/lyz/code/Ragent/mcp-server
  nohup java -jar target/mcp-server-0.0.1-SNAPSHOT.jar > /tmp/mcp-server.log 2>&1 &
  ```
  实测 2.914s 启动完成，Tomcat 监听 9099。

- [x] 端到端验证 —— 改用脚本一次跑完全部用例（比逐步 curl 更好留证）：
  ```bash
  python3 /tmp/mcp_e2e.py | tee /tmp/mcp_e2e_out.txt
  ```
  - 完成 `initialize`（拿 `mcp-session-id`）→ `notifications/initialized` → `tools/list` → 6 次 `tools/call`
  - 坑：响应是 SSE 帧（`id:` / `event: message` / `data: {...}`），**不能直接 `json.loads` 整个响应体**，
    需按行取 `data:` 再解析。

- [x] `tools/list` 仍为 7 个工具，两个 guli 工具的 schema 未变 → 回归通过
- [x] `product_detail_query {"skuId":1}` → 真实商品名，`isError=false` → AC5 / AC7
- [x] `product_stock_query {"skuId":1}` → 120/5/115 + 40/10/30 + 汇总 160/15/145，`isError=false` → AC6 / AC7
- [x] `product_stock_query {"skuId":1,"wareId":2}` → 过滤后 1 条，"🟡 库存较少"分支
- [x] `product_detail_query {"skuId":0}` → `isError=true`，`请提供有效的 SKU ID` → AC8
- [x] `product_detail_query {"skuId":999999}` → `isError=true`，`异步读取sku信息失败（code=10002）` → AC8
- [x] 额外用例 `product_stock_query {"skuId":999999}` → `isError=false`，`该 SKU 没有库存记录`
      （库存接口对不存在的 SKU 返回空 `rows`+`code=200`，属正常空结果，非错误）

## S9 收尾取证

- [x] 记录实际启动的服务清单（对应 AC9）——仅 3 个 Java 进程（9214/9215/9099）+ 既有容器，**未启动网关**
- [x] 保存两个工具的真实返回样本（见 prd.md 验收证据表、`/tmp/mcp_e2e_out.txt`）
- [x] 更新 prd.md / implement.md / design.md 中的实测结论
- [ ] 清理：按 prd.md 顺序停进程；**容器是否停由用户决定**
- [ ] 更新 spec（Phase 3.3）
- [ ] 提交（Phase 3.4）

---

## 危险点与回滚点汇总

| 位置 | 风险 | 回滚 |
|---|---|---|
| S1 删 mysql.sock 软链接 | 误删真实数据文件 | 删前 `ls -la` 确认是软链接；数据目录不动其他文件 |
| S3 导入业务表 | 表已存在时报错 | 导入不删既有数据；seed 可用 DELETE 撤销 |
| S4 起 guli-product | ES 阻塞 → 可能想擅自改源码/起 ES | **判定树明确禁止**，回到用户确认 |
| S7 改 executor | 解析改错导致回归 | 仅改两个 build*Result 方法 + 错误分支，单文件 git checkout 可回滚；S6 已固定接口契约 |

## task.py start 前的检查

- [ ] prd.md 无阻塞 Open Question（ES 一条为条件性预案，不阻塞开工）
- [ ] design.md / implement.md 已就绪
- [ ] implement.jsonl / check.jsonl 已填写真实 spec 条目
- [ ] 用户已明确批准最新规划摘要
