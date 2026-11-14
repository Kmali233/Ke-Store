# 订单服务：接口契约与开发阅读顺序

## 阶段13A：虚拟商品结算与物流退役

升级时先停止旧写服务，备份数据库，按迁移入口应用至 `V15__digital_order_checkout.sql` 后同步更新 order、admin 和前端。V15 将历史收货字段改为可空，把原发货授权迁至 `order:read`。后台分页、详情及关单记录使用该权限。新订单无需地址，订单查询省略物流与收货字段。状态码 2、3、4 只解释为历史备货、发货、签收。历史数据及 V1–V14 迁移保留。

新客户端仅发送明细与可选备注。旧客户端仍可带原格式收货参数，以原摘要回查及重试，服务端不读取地址簿或保存其地址快照。数字交付见计划阶段14A。

## 整单退款与对账

| 方法与路径（含 /api） | 权限 | 成功结果 |
|---|---|---|
| GET /api/order/{orderNo}/refund | 本人买家 | 可申请标识、退款状态、金额、退款请求号及更新时间 |
| POST /api/order/{orderNo}/refund | 本人买家 | 创建整单申请，重复提交返回原退款单 |
| GET /api/admin/orders/refunds | `refund:review` | 可按 `status`、`orderNo` 筛选，`page` 从 1 开始，每页 50 条 |
| POST /api/admin/orders/{orderNo}/refund/{approve\|reject\|retry} | `refund:review` | 同意、拒绝或重查并按原号重试退款 |
| GET /api/admin/orders/reconciliation | `trade:reconcile` | 最近对账结果、次数和 UTC 核对时间，支持 `orderNo`、`differences`、`page` |
| POST /api/admin/orders/reconciliation/run | `trade:reconcile` | 本批至多核对三张单据，各单独提交结果 |
| POST /api/admin/orders/{orderNo}/reconciliation/{kind} | `trade:reconcile` | 指定单据主动核对，`kind` 为 `PAYMENT` 或 `REFUND` |

买家写接口使用服务端登录身份及订单号，使用空请求体。订单不存在或不属于当前买家时返回 404。

退款金额取已成功支付单的全额，校验与订单金额一致。退款申请锁定订单行。当前已成功付款的订单可按状态申请，旧状态 2 保持原退款资格，历史发货和签收订单不进入这条退款路径。同一订单保留一张退款单，拒绝后买家重试仍读到已拒绝结果。审核同意先提交 `PROCESSING`，再用固定退款号调用通道。付款单保留原入账证据。退款查询确认同一交易、同一退款号和全额到账后，在一笔本地事务中关单、写入 `REFUND_RESTORE` 库存流水、更新退款成功。已付款线上订单直接取消返回 409 并引导退款。待付款取消继续使用关单流程。

通道结果未知时保留处理中。审核员可到交易对账只重查，或在退款审核页先查后按原号重试。查询异常、金额和身份异常保留核对结果及审计，确认通道失败时由售后重查并核对原交易。支付补偿使用通知与买家查询共用的入账逻辑，退款补偿使用审核查询共用的本地收尾逻辑。批次优先处理未核对及最久未核对的单据，进程中断后可继续运行下一批。两种核对分别写入后台审计。

## 原商城本地模拟支付

订单服务仅在本机回环地址上使用 `dev,payment-local` 启用。原商城买家打开本人尚未到期、未进入关单且未创建支付单的待付款订单，可以点击「模拟支付成功」。状态查询返回 `canSimulate`，买家确认后调用 `POST /api/order/{orderNo}/pay`，由服务端将订单更新为已付款。这个入口不会生成通道支付单或真实交易。

## 阶段10.5：演示支付

使用独立的 `xiaoke_demo_*` 数据库，迁移至当前版本。仅订单服务使用 `SPRING_PROFILES_ACTIVE=dev,payment-demo`。同一订单实例只能启用一种测试支付模式。买家登录后在订单详情点击「去演示支付」，从同站点进入演示收银台。收银台使用服务端的测试密钥生成 RSA2 签名，沿用支付单验签与入账流程。

| 方法与路径 | 成功结果 |
|---|---|
| POST /api/order/{orderNo}/payment/demo | 生成或复用支付单，返回站内收银台地址 |
| POST /api/order/{orderNo}/payment/demo/confirm/notify | 记录模拟付款，投递进程内签名通知并确认 |
| POST /api/order/{orderNo}/payment/demo/confirm/query | 只记录模拟付款，等待买家主动查询 |
| POST /api/order/{orderNo}/payment/query | 查演示回执并确认付款，返回本地状态 |

接口均要求本人买家 JWT。POST 不接收金额或其他请求体。状态接口新增 `demoEnabled` 和 `channel`，演示支付单的通道为 `DEMO`。模拟付款回执持久化到 `t_demo_payment_trade`，进程重启后可补查。演示通知在同一个服务进程内投递与验签，服务器部署使用同站点 HTTPS 反向代理，不需要额外的外部通知入口。

部署说明与验证见 [演示支付详细实现说明](../../基础模块后阶段计划/10.5-演示支付模块/详细实现说明.md)。

## 阶段11：支付宝沙箱支付

部署订单服务前将共享库迁移至当前版本。订单服务默认关闭支付。联调时将订单服务的 `SPRING_PROFILES_ACTIVE` 设为 `dev,alipay-sandbox`，用进程环境变量配置 `.env.example` 中的 `XIAOKE_ALIPAY_*`。商户 PID、App ID、应用 PKCS#8 私钥和支付宝公钥取自同一沙箱应用，密钥变量使用单行 Base64。支付宝公钥由沙箱平台提供。网关限 HTTPS 沙箱白名单。带有 `prod` 或同时选择 `payment-test` 的配置不会装配沙箱通道。

买家端从仓库根目录用 `pnpm dev` 启动。将 `XIAOKE_ALIPAY_BUYER_ORIGIN` 配为实际买家端 origin，默认 `http://127.0.0.1:5173`。配置 `XIAOKE_ALIPAY_NOTIFY_URL` 为公网可达的专用 HTTPS 地址，路径固定 `/api/payment/alipay/notify`。调试入口仅转发该路径的 POST 到 order 服务。浏览器回到订单页会调用商城查询，随后重读订单状态。Spring Boot 通过进程环境变量读取这些值。

| 方法与路径 | 身份和输入 | 成功结果 |
|---|---|---|
| POST /api/order/{orderNo}/payment/alipay | 买家 JWT，无请求体或查询参数 | `paymentUrl`，支付宝沙箱签名收银台链接 |
| GET /api/order/{orderNo}/payment | 买家 JWT，无查询参数 | 本地 `sandboxEnabled/canStart/state` |
| POST /api/order/{orderNo}/payment/query | 买家 JWT，无请求体或查询参数 | 同上，待确认支付单先由服务端查支付宝 |
| POST /api/payment/alipay/notify | 支付宝 RSA2 签名表单 | HTTP 200，纯文本 `success` 或 `failure` |
| POST /api/order/{orderNo}/payment/wechat | 买家 JWT，无请求体或查询参数 | 通道禁用，返回 409 |

状态 `NONE` 表示尚无支付单，`PENDING` 表示本地尚未确认付款，`SUCCEEDED` 表示已确认付款。收银台请求使用订单金额快照，同一订单复用同一支付单号。异步通知校验应用、卖家 PID、金额、订单和交易状态。主动查询通过该支付单绑定的直接商户应用发起签名请求，由 SDK 验证成功响应，再匹配支付单号与金额。两个入口均在一个事务内推进订单和支付单。支付宝返回未付款、已关闭或尚无交易时，商城继续保留支付单，等待阶段12协调关单。

原 `POST /api/order/{orderNo}/pay` 仅供 `dev,payment-test` 回归测试。普通取消只允许无支付单的待付款订单。测试配置保留无支付单的旧模拟订单取消语义。有支付单的订单通过数据库行锁与发起支付串行检查，任何配置下均不能通过原入口直接取消。

## 阶段08：购物车

购物车由 order 服务（8082）提供。所有请求使用买家 JWT，身份从登录上下文取得。接口拒绝额外的查询参数和 JSON 字段。

| 方法与路径 | 请求 | 成功 data |
|---|---|---|
| GET /api/cart | 无 | SKU 条目数组，按 skuId 升序 |
| POST /api/cart/items | `{"skuId":123,"quantity":2}` | null |
| PATCH /api/cart/items/{skuId} | `{"quantity":3,"selected":false}`，至少提供一个非 null 字段 | null |
| DELETE /api/cart/items/{skuId} | 无 | null |
| POST /api/cart/checkout | 必填 `Idempotency-Key`，`{"items":[{"skuId":123,"quantity":3}]}`，可选 `remark` | 首次下单回执 `orderNo/status/totalAmount` |

- 单个 SKU 数量为 1–999。重复加购合并数量并保留原勾选状态，新条目默认勾选。合并超限返回 409，修改或删除不存在的本人条目返回 404。加购校验商品、SKU 和所属分类可用性，库存不足的条目可以保留在清单里。
- 查询随当前商品目录返回 `productId`、`productName`、`skuName`、`skuSpec`、`image`、`price`、`stock` 与 `availability`。状态包括 `AVAILABLE`（满足购物车数量）、`OFF_SHELF`（商品、分类或 SKU 停用）、`OUT_OF_STOCK`（库存低于数量）、`INVALID_SKU`（SKU 或商品缺失或删除）。缺失规格时相关展示字段可为空，仍可删除条目。
- 结算提交查询结果中全部已勾选的 SKU 与数量，最多 20 项，SKU 不重复。选项或数量改变时返回 409，客户端重新查询并请买家确认。客户端不提交用户 ID、价格或应付金额。加购与编辑不扣库存，订单事务根据锁定后的价格、状态与库存重新计算、校验并下单。
- 结算复用同一用户下单键空间，免地址结算用摘要版本 5，直购用版本 4，旧结算用版本 3。跨入口用同一键、或复用已成功的键却改变内容，会返回 409。订单、库存、回执与已勾选条目清理一起提交，未勾选项保留。未知结果先查 `GET /api/order/idempotency/{key}`。查询 404 时保留原键和原请求，稍后再查或用原内容重试。成功重放只返回回执。

部署前应用 `V8__shopping_cart.sql`。购物车表以 `(user_id, sku_id)` 为主键，数据和订单同库。验收入口为 `python scripts/verify-system.py --workers 4 --rounds 1`。

阶段06：本模块重要管理写入通过 `xiaoke-audit` 在原事务内记录成功事件；审计失败则业务回滚。失败访问记录独立安全日志。当前部署先迁移V6，具体查询与保留策略见[审计说明](../../基础模块后阶段计划/06-管理操作审计模块/详细实现说明.md)。

## 阶段03更新：SKU下单与旧请求兼容（2026-10-28）

- 新明细使用`{"skuId":123,"quantity":2}`；旧明细`{"productId":1,"quantity":2}`只映射该商品固定默认SKU。每项必须二选一，不得同时填写或都省略；不同SKU可属于同一个SPU。两种选择器最终指向同一SKU时也拒绝重复明细400。
- 新创建的全部明细（包括旧入口）保存skuId、skuName、skuSpec及商品名/图片/单价快照。详情新增这三个字段；skuSpec是规范化JSON字符串。历史记录三字段为null时仍按原快照查询，取消时只映射原商品默认SKU；不会用当前价格重算旧订单。
- 新交易只读写t_product_sku价格/stock/sales；订单对SPU只读/锁定名称、图片、归属分类和可见性，不再读写SPU旧库存。锁序：用户→幂等回执→分类升序→SPU升序→SKU升序，取消为用户→订单→SPU升序→SKU升序。
- 取消恢复原SKU，不因SPU/SKU下架或软删而跳过；SKU/商品物理缺失或归属不一致500并回滚，不悄悄恢复到其他规格。
- 全productId请求保持阶段02 **v1摘要完全不变**，已有回执仍可重放。包含任意skuId的新请求使用v2，按选择器类型/ID排序，包含`product`或`sku`标签、ID及数量；同键从productId改为skuId即使指向默认SKU也返回409，不改变旧键含义。
- SKU解析只用于首次请求，成功回执先于商品/SKU读取，因此旧键不因商品或SKU下架而丢失。SKU模式按商品ID去重HTTP预读，每个SPU最多一次；数据库锁后读取SKU才是价格与库存权威。

部署前停止所有旧product/order写进程，经迁移入口应用V3并完成默认SKU分批回填与核对，再启动新版。只执行Flyway DDL不足以完成切换，`t_catalog_migration`中应有version=3完成标记。旧二进制继续写t_product库存会破坏账本，不支持混合版本滚动写入。详细代码和验证见[阶段03说明](../xiaoke-product/详细实现说明.md)。

下文阶段02的历史设计说明中，商品价格/库存权威和锁序以本节为准；原有幂等生命周期、身份、状态机与订单号规则保留。

更新日期：2026-10-27。端口8082，统一前缀/api。order拥有t_orders/t_order_item/t_order_idempotency，通过自有适配Mapper在同一数据源访问user/category/product；没有依赖user/product jar，没有增加第三方生产依赖。阶段02新增V2幂等回执表，原V1保持不变。详细代码、调用链和验证见 [详细实现说明.md](详细实现说明.md)。

## 基础订单接口

| 方法与路径（含/api） | 权限 | 输入 | 成功data |
|---|---|---|---|
| POST /api/order | 买家JWT | OrderCreateDTO；Idempotency-Key请求头可选（新客户端必须带） | 首次创建结果：orderNo、status、totalAmount |
| GET /api/order/idempotency/{key} | 买家JWT | 本人使用过的键 | 原始创建结果；尚未可见404 |
| GET /api/order/page | 买家JWT | pageNum=1、pageSize=10、status? | 分页OrderSummaryVO |
| GET /api/order/{orderNo} | 买家JWT | 14位订单号 | OrderDetailVO |
| GET /api/order/{orderNo}/status | 买家JWT | 14位订单号 | 状态、商品名与数量 |
| POST /api/order/{orderNo}/pay | 买家JWT + 独立 payment-test 配置 | 无body/query | null |
| POST /api/order/{orderNo}/cancel | 买家JWT | 无body/query | null |

成功HTTP200/code200/message“操作成功”；错误HTTP与code一致、data=null。JWT请求头是Authorization: 原始JWT，没有Bearer前缀。后台订单查询要求独立后台 JWT 和 `order:read` 权限；无效身份401，权限不足403。

七个买家接口每次都查数据库账号；不存在/已删401、禁用403。本人不存在、已删和他人的订单均404。幂等查询仅按当前用户查询回执，不返回其他用户结果。客户端不能指定userId。

## 下单请求幂等（阶段02）

- 新客户端在首次提交前生成UUID等随机键，例如 `Idempotency-Key: 7d932925-410c-471b-aa25-33cfc5dac83c`；同一业务意图的重复点击、超时重发必须复用原键和原内容。开始另一笔订单才生成新键。服务端仍允许无键旧直购调用；无键调用两次可创建两单，不享受幂等保证。
- 键只接受1–64位ASCII字母、数字、`_`、`-`，不trim、区分大小写；空键、非法字符、过长、重复头或逗号拼接头400。作用域为当前登录用户＋原始键，不同用户可使用相同键。
- 同用户同键同规范化内容返回200及首次`orderNo/status/totalAmount`。同键异参409。结果中的status是首次创建状态0；当前支付/取消状态请查订单状态接口。订单被取消或软删后仍返回原创建回执，不重新创建或扣库存。
- 摘要v1采用UTF-8 JSON位置数组＋SHA-256：版本、按productId升序的商品ID/数量、收货来源（addressId引用或trim后的三个手填字段）、trim后的备注（空白与null等价）。JSON字段顺序、商品排列不影响摘要；数量、商品、备注、地址ID或手填内容变化会冲突。引用地址的当前内容、商品价格/状态不进入摘要，以首次成功时的快照为准。
- 旧版回执保留原 `hash_version`。免地址直购和结算的摘要包含版本、规范排序的 SKU 或商品选择、数量和备注。当前遇到未知摘要版本409并保留旧键，不以新含义重新下单。摘要不保存完整收货信息。
- 回执与订单、明细、库存变化在同一MySQL事务提交。先查回执的快速重放不调用商品HTTP；首次未命中仍执行现有HTTP预读，写事务锁用户后再次检查回执。跨进程正确性依赖共享MySQL用户行锁和回执联合主键，JVM锁只用于既有单号分配。
- 成功回执永久保留，不设TTL、不提供清理任务；键没有“到期后复用”语义。不得因订单软删/归档删掉回执；未来如需清理须保留不可复用的墓碑与结果查询策略。业务库的物理删库或人工删回执会破坏保证。
- 已确认事务回滚：没有成功回执，可修复故障后用原键原内容重试。连接中断或提交结果未知：先`GET /api/order/idempotency/{key}`，200表示已成功；404只表示此时未看到已提交回执，可能仍在执行，不能推断已经回滚。保留原键，稍后再查；需要重发时仍只用原键原内容。数据库不可用时查询可能503，禁止换新键盲目再下单。服务端不会在提交结果未知时自动重做写事务。

上线顺序：备份并应用 `sql/migration/V2__order_idempotency.sql`（通过迁移入口），确认Flyway validate通过，再部署新order。旧版本无键调用在新增表后仍可运行；成功上线后旧二进制不处理新键，不能把业务流量退回旧版本并继续宣称幂等。代码回退不删除V2或历史回执。

## 创建请求

~~~json
{
  "items": [{"skuId": 123, "quantity": 2}],
  "remark": "可选备注"
}
~~~

明细为 1–20 项，每项使用正整数 SKU ID 或兼容的商品 ID，数量为 1–999，同一 SKU 不能重复。备注去除首尾空白，最长 255 字符。价格、应付金额、账号和订单状态由服务端确定。

金额BigDecimal，不舍入。单项金额和总额不超过99999999.99；超过返回400，数据库已有非法单价/负计数返回500。

## 状态规则

| 值 | 含义 | 本期允许动作 |
|---|---|---|
| 0 | 待付款 | 支付→1；取消→5 |
| 1 | 已付款 | 整单退款成功→5 |
| 2 | 历史备货 | 整单退款成功→5 |
| 3 | 历史发货 | 查询 |
| 4 | 历史签收 | 查询 |
| 5 | 已关闭 | 查询；根据独立退款单区分已退款和普通取消 |

隔离的 payment-test 保留模拟支付和取消契约。订单状态查询只读。

## 事务、库存和单号

创建Facade先校验参数和账号，在事务与按日锁外调用现有商品详情接口。每商品最多一次、最多20次，无HTTP重试和重定向。连接预算1秒、单次完整响应最多2秒、整轮最多5秒；上游404→404，超时/故障/畸形或状态不一致→503。

预读成功不预留库存。创建写事务的锁序为：用户→幂等回执→分类ID升序→商品ID升序→SKU ID升序。锁后重新检查祖先链、商品状态、价格、库存和下单件数。分类链或商品分类变化返回409，不在商品锁后补分类锁。

库存扣减stock-q、sales+q，主表与全部明细写入共用同一连接、同一事务。外部字段orderCount对应sales，表示已创建且未取消的下单件数。支付不改变它。

取消先锁用户→本人订单→商品ID升序，不锁分类；检查全部明细完整性和计数边界，CAS置5并恢复stock+q/sales-q。恢复包含软删和下架商品，缺商品/损坏明细500，溢出或不足409，任何失败回滚整笔。

OrderTransactions使用TransactionTemplate：创建REQUIRES_NEW/READ_COMMITTED，其他写RC，三种组合查询只读REPEATABLE_READ。事务完成后才返回；common确认整笔回滚后将MySQL1205/1213转409，连接不可用503，提交结果未知503且不自动重试。

单号为Asia/Shanghai日期yyyyMMdd+六位序号。MAX包含软删记录，999999耗尽409，同日畸形历史单号500。Facade按日JVM锁一直持有到事务提交或回滚；只有MySQL1062且确认为uk_order_no，才在回滚后重新开完整事务，最多三次总尝试。

订单号分配仍是当前单实例方案，多实例只靠订单号唯一索引和有限重试兜底，不承诺高吞吐；按日锁容器未作过期清理。下单请求幂等由独立的用户＋键持久化回执保证；无键旧调用仍可能生成两单。带键提交结果未知时按上文先查键，不换新键重下。

## 查询与错误

分页只查主表，SQL同时过滤owner、deleted和可选status，create_time DESC,id DESC。pageSize为1–100，超末页保留页号、返回空records。详情和状态在同一RR快照内读订单和明细，不关联当前商品。VO不返回内部id/userId/deleted。

400：非法输入；401：凭证或账号失效；403：账号禁用/管理关闭；404：订单或公开商品不可用；409：状态、库存、链变化或已确认回滚的锁冲突；500：内部或数据损坏；503：商品HTTP或数据库基础设施故障、事务结果未知。

## 启动和阅读

从仓库根目录执行：

~~~powershell
mvn -B -ntp -pl xiaoke-order -am package
java -jar xiaoke-order/target/xiaoke-order-1.0.0.jar
~~~

dev设置DB_USERNAME、DB_PASSWORD，可选DB_HOST/DB_PORT/DB_NAME。设置与user相同的XIAOKE_JWT_SECRET_BASE64，解码至少32字节。PRODUCT_BASE_URL默认http://127.0.0.1:8081/api，创建订单需要商品服务可达；查询/支付/取消不调用商品HTTP。prod要求完整DB_URL和PRODUCT_BASE_URL，不使用默认数据库凭据。

后台订单查询需启用后台认证，设置与 admin 一致的后台 JWT 密钥，并应用 V15 权限迁移。

初学先读OrderController.detail→OrderQueryService→DefaultOrderQueryService.detail→OrdersMapper.selectOwned→Orders/OrderItem与VO。然后依次看分页、状态枚举、支付、创建编排、创建事务、取消、单号和并发测试。

实际开发顺序：表/契约→实体和DTO/VO→自有适配Mapper→查询与事务入口→创建Facade/单号→创建事务→支付/取消→Controller→测试。

普通mvn package默认跳过真实数据库测试。启用需要XIAOKE_ORDER_DB_TESTS=true、XIAOKE_ORDER_TEST_DB_URL，库名必须匹配xiaoke_order_verify_*；测试会清空这个专用库并注入故障，测试使用独立实例root/空口令，不得指向业务环境。从仓库根目录运行 `python scripts/sync-test-schemas.py --check` 核对全部迁移生成的测试结构，再运行 `python scripts/verify-idempotency.py`，包括四模块真实数据库测试、四个实际JAR进程（其中两个order）、重启、响应丢弃与回滚验证。
