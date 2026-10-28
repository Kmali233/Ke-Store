# 订单服务：接口契约与开发阅读顺序

## 阶段03更新：SKU下单与旧请求兼容（2026-10-28）

- 新明细使用`{"skuId":123,"quantity":2}`；旧明细`{"productId":1,"quantity":2}`只映射该商品固定默认SKU。每项必须二选一，不得同时填写或都省略；不同SKU可属于同一个SPU。两种选择器最终指向同一SKU时也拒绝重复明细400。
- 新创建的全部明细（包括旧入口）保存skuId、skuName、skuSpec及商品名/图片/单价快照。详情新增这三个字段；skuSpec是规范化JSON字符串。历史记录三字段为null时仍按原快照查询，取消时只映射原商品默认SKU；不会用当前价格重算旧订单。
- 新交易只读写t_product_sku价格/stock/sales；订单对SPU只读/锁定名称、图片、归属分类和可见性，不再读写SPU旧库存。锁序：用户→幂等回执→地址→分类升序→SPU升序→SKU升序，取消为用户→订单→SPU升序→SKU升序。
- 取消恢复原SKU，不因SPU/SKU下架或软删而跳过；SKU/商品物理缺失或归属不一致500并回滚，不悄悄恢复到其他规格。
- 全productId请求保持阶段02 **v1摘要完全不变**，已有回执仍可重放。包含任意skuId的新请求使用v2，按选择器类型/ID排序，包含`product`或`sku`标签、ID及数量；同键从productId改为skuId即使指向默认SKU也返回409，不改变旧键含义。
- SKU解析只用于首次请求，成功回执先于商品/SKU读取，因此旧键不因商品或SKU下架而丢失。SKU模式按商品ID去重HTTP预读，每个SPU最多一次；数据库锁后读取SKU才是价格与库存权威。

部署前停止所有旧product/order写进程，经迁移入口应用V3并完成默认SKU分批回填与核对，再启动新版。只执行Flyway DDL不足以完成切换，`t_catalog_migration`中应有version=3完成标记。旧二进制继续写t_product库存会破坏账本，不支持混合版本滚动写入。详细代码和验证见[阶段03说明](../xiaoke-product/详细实现说明.md)。

下文阶段02的历史设计说明中，商品价格/库存权威和锁序以本节为准；原有幂等生命周期、身份、状态机与订单号规则保留。

更新日期：2026-10-27。端口8082，统一前缀/api。order拥有t_orders/t_order_item/t_order_idempotency，通过自有适配Mapper在同一数据源访问user/address/category/product；没有依赖user/product jar，没有增加第三方生产依赖。阶段02新增V2幂等回执表，原V1保持不变。详细代码、调用链和验证见 [详细实现说明.md](详细实现说明.md)。

## 八个接口

| 方法与路径（含/api） | 权限 | 输入 | 成功data |
|---|---|---|---|
| POST /api/order | 买家JWT | OrderCreateDTO；Idempotency-Key请求头可选（新客户端必须带） | 首次创建结果：orderNo、status、totalAmount |
| GET /api/order/idempotency/{key} | 买家JWT | 本人使用过的键 | 原始创建结果；尚未可见404 |
| GET /api/order/page | 买家JWT | pageNum=1、pageSize=10、status? | 分页OrderSummaryVO |
| GET /api/order/{orderNo} | 买家JWT | 14位订单号 | OrderDetailVO |
| GET /api/order/{orderNo}/status | 买家JWT | 14位订单号 | 状态、物流、商品名与数量 |
| POST /api/order/{orderNo}/pay | 买家JWT | 无body/query | null |
| POST /api/order/{orderNo}/ship | 本地管理 | logisticsCompany、trackingNo | null |
| POST /api/order/{orderNo}/cancel | 买家JWT | 无body/query | null |

成功HTTP200/code200/message“操作成功”；错误HTTP与code一致、data=null。JWT请求头是Authorization: 原始JWT，没有Bearer前缀。发货只接受common的本地管理门禁，不要求买家JWT；默认关闭403，显式dev、本机IPv4、开关及服务端密钥齐全后，缺失或错误请求密钥401。

七个买家接口每次都查数据库账号；不存在/已删401、禁用403。本人不存在、已删和他人的订单均404。幂等查询仅按当前用户查询回执，不返回其他用户结果。客户端不能指定userId。

## 下单请求幂等（阶段02）

- 新客户端在首次提交前生成UUID等随机键，例如 `Idempotency-Key: 7d932925-410c-471b-aa25-33cfc5dac83c`；同一业务意图的重复点击、超时重发必须复用原键和原内容。开始另一笔订单才生成新键。当前没有新版下单UI，服务端暂允许无键旧调用；无键调用两次可创建两单，不享受幂等保证。
- 键只接受1–64位ASCII字母、数字、`_`、`-`，不trim、区分大小写；空键、非法字符、过长、重复头或逗号拼接头400。作用域为当前登录用户＋原始键，不同用户可使用相同键。
- 同用户同键同规范化内容返回200及首次`orderNo/status/totalAmount`。同键异参409。结果中的status是首次创建状态0；当前支付/取消状态请查订单状态接口。订单被取消或软删后仍返回原创建回执，不重新创建或扣库存。
- 摘要v1采用UTF-8 JSON位置数组＋SHA-256：版本、按productId升序的商品ID/数量、收货来源（addressId引用或trim后的三个手填字段）、trim后的备注（空白与null等价）。JSON字段顺序、商品排列不影响摘要；数量、商品、备注、地址ID或手填内容变化会冲突。引用地址的当前内容、商品价格/状态不进入摘要，以首次成功时的快照为准。
- 回执存`hash_version=1`；以后升级SKU等必须保留旧版摘要解释能力。当前遇到未知摘要版本409并保留旧键，不以新含义重新下单。摘要不保存完整收货信息。
- 回执与订单、明细、库存变化在同一MySQL事务提交。先查回执的快速重放不调用商品HTTP；首次未命中仍执行现有HTTP预读，写事务锁用户后再次检查回执。跨进程正确性依赖共享MySQL用户行锁和回执联合主键，JVM锁只用于既有单号分配。
- 成功回执永久保留，不设TTL、不提供清理任务；键没有“到期后复用”语义。不得因订单软删/归档删掉回执；未来如需清理须保留不可复用的墓碑与结果查询策略。业务库的物理删库或人工删回执会破坏保证。
- 已确认事务回滚：没有成功回执，可修复故障后用原键原内容重试。连接中断或提交结果未知：先`GET /api/order/idempotency/{key}`，200表示已成功；404只表示此时未看到已提交回执，可能仍在执行，不能推断已经回滚。保留原键，稍后再查；需要重发时仍只用原键原内容。数据库不可用时查询可能503，禁止换新键盲目再下单。服务端不会在提交结果未知时自动重做写事务。

上线顺序：备份并应用 `sql/migration/V2__order_idempotency.sql`（通过迁移入口），确认Flyway validate通过，再部署新order。旧版本无键调用在新增表后仍可运行；成功上线后旧二进制不处理新键，不能把业务流量退回旧版本并继续宣称幂等。代码回退不删除V2或历史回执。

## 创建请求

直接收货模式：

~~~json
{
  "items": [{"productId": 1, "quantity": 2}],
  "receiverName": "测试人",
  "receiverPhone": "123",
  "receiverAddress": "测试路",
  "remark": "可选备注"
}
~~~

引用本人地址模式：

~~~json
{
  "items": [{"productId": 1, "quantity": 2}],
  "addressId": 1
}
~~~

两种模式严格二选一。未使用的字段应省略；显式addressId:null、引用模式同时发送receiverName:null等也返回400。不会自动选择默认地址。

items为1–20项，不能有null项或重复productId。productId是正Long；quantity是1–999的整数，拒绝字符串、小数、布尔和溢出。收货姓名/电话/地址trim后必填，分别最长50/20/255；备注trim空白转null、最长255。引用地址必须属于本人且未删，省/市/区/详细地址以单个空格连接，再核对总长度。

客户端price、totalAmount、status、orderNo、userId、deleted和时间等字段均拒绝。商品名、图片、单价和收货信息保存下单快照，之后商品和地址改删不回写历史订单。

金额BigDecimal，不舍入。单项金额和总额不超过99999999.99；超过返回400，数据库已有非法单价/负计数返回500。

## 状态规则

| 值 | 含义 | 本期允许动作 |
|---|---|---|
| 0 | 待付款 | 支付→1；取消→5 |
| 1 | 已付款 | 发货→3；取消→5 |
| 2 | 备货中，预留 | 发货→3；取消→5 |
| 3 | 已发货 | 只查询 |
| 4 | 已签收，预留 | 只查询 |
| 5 | 已取消 | 只查询 |

没有进入2/4的新接口。支付为模拟动作，不扣真实资金；取消已付款订单保留payTime，不执行真实退款。重复支付、重复发货、重复取消或不允许的状态转换返回409。

发货请求示例：

~~~json
{"logisticsCompany":"测试物流","trackingNo":"TEST001"}
~~~

两个字符串trim后必填，最长50/64；一次写入物流与shipTime，不覆盖已发货物流。状态查询只读，不自动推进状态，也不调用物流服务。

## 事务、库存和单号

创建Facade先校验参数和账号，在事务与按日锁外调用现有商品详情接口。每商品最多一次、最多20次，无HTTP重试和重定向。连接预算1秒、单次完整响应最多2秒、整轮最多5秒；上游404→404，超时/故障/畸形或状态不一致→503。

预读成功不预留库存。创建写事务的锁序为：用户→本人地址读取→分类ID升序→商品ID升序。锁后重新检查祖先链、商品状态、价格、库存和下单件数。分类链或商品分类变化返回409，不在商品锁后补分类锁。

库存扣减stock-q、sales+q，主表与全部明细写入共用同一连接、同一事务。外部字段orderCount对应sales，表示已创建且未取消的下单件数。支付和发货不改变它。

取消先锁用户→本人订单→商品ID升序，不锁分类；检查全部明细完整性和计数边界，CAS置5并恢复stock+q/sales-q。恢复包含软删和下架商品，缺商品/损坏明细500，溢出或不足409，任何失败回滚整笔。

OrderTransactions使用TransactionTemplate：创建REQUIRES_NEW/READ_COMMITTED，其他写RC，三种组合查询只读REPEATABLE_READ。事务完成后才返回；common确认整笔回滚后将MySQL1205/1213转409，连接不可用503，提交结果未知503且不自动重试。

单号为Asia/Shanghai日期yyyyMMdd+六位序号。MAX包含软删记录，999999耗尽409，同日畸形历史单号500。Facade按日JVM锁一直持有到事务提交或回滚；只有MySQL1062且确认为uk_order_no，才在回滚后重新开完整事务，最多三次总尝试。

订单号分配仍是当前单实例方案，多实例只靠订单号唯一索引和有限重试兜底，不承诺高吞吐；按日锁容器未作过期清理。下单请求幂等由独立的用户＋键持久化回执保证；无键旧调用仍可能生成两单。带键提交结果未知时按上文先查键，不换新键重下。

## 查询与错误

分页只查主表，SQL同时过滤owner、deleted和可选status，create_time DESC,id DESC。pageSize为1–100，超末页保留页号、返回空records。详情和状态在同一RR快照内读订单和明细，不关联当前商品。VO不返回内部id/userId/deleted。

400：非法输入；401：凭证或账号失效；403：账号禁用/管理关闭；404：订单、地址或公开商品不可用；409：状态、库存、链变化或已确认回滚的锁冲突；500：内部或数据损坏；503：商品HTTP或数据库基础设施故障、事务结果未知。

## 启动和阅读

从仓库根目录执行：

~~~powershell
mvn -B -ntp -pl xiaoke-order -am package
java -jar xiaoke-order/target/xiaoke-order-1.0.0.jar
~~~

dev设置DB_USERNAME、DB_PASSWORD，可选DB_HOST/DB_PORT/DB_NAME。设置与user相同的XIAOKE_JWT_SECRET_BASE64，解码至少32字节。PRODUCT_BASE_URL默认http://127.0.0.1:8081/api，创建订单需要商品服务可达；查询/支付/发货/取消不调用商品HTTP。prod要求完整DB_URL和PRODUCT_BASE_URL，不使用默认数据库凭据。

本地发货需SPRING_PROFILES_ACTIVE=dev、XIAOKE_LOCAL_ADMIN_ENABLED=true、LOCAL_ADMIN_KEY；请求传X-Local-Admin-Key。任何激活prod的情况都拒绝本地发货管理操作。

初学先读OrderController.detail→OrderQueryService→DefaultOrderQueryService.detail→OrdersMapper.selectOwned→Orders/OrderItem与VO。然后依次看分页、状态枚举、支付、创建编排、创建事务、取消、单号和并发测试。

实际开发顺序：表/契约→实体和DTO/VO→自有适配Mapper→查询与事务入口→创建Facade/单号→创建事务→支付/发货/取消→Controller→测试。

普通mvn package默认跳过真实数据库测试。启用需要XIAOKE_ORDER_DB_TESTS=true、XIAOKE_ORDER_TEST_DB_URL，库名必须匹配xiaoke_order_verify_*；测试会清空这个专用库并注入故障，测试使用独立实例root/空口令，不得指向业务环境。从仓库根目录运行 `python scripts/sync-test-schemas.py --check` 核对全部迁移生成的测试结构，再运行 `python scripts/verify-idempotency.py`，包括四模块真实数据库测试、四个实际JAR进程（其中两个order）、重启、响应丢弃与回滚验证。
