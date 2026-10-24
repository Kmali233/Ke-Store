# 订单服务：接口契约与开发阅读顺序

更新日期：2026-10-24。端口8082，统一前缀/api。order拥有t_orders/t_order_item，通过自有适配Mapper在同一数据源访问user/address/category/product；没有依赖user/product jar，也没有增加第三方生产依赖或修改七张业务表的DDL。

## 七个接口

| 方法与路径（含/api） | 权限 | 输入 | 成功data |
|---|---|---|---|
| POST /api/order | 买家JWT | OrderCreateDTO | orderNo、status、totalAmount |
| GET /api/order/page | 买家JWT | pageNum=1、pageSize=10、status? | 分页OrderSummaryVO |
| GET /api/order/{orderNo} | 买家JWT | 14位订单号 | OrderDetailVO |
| GET /api/order/{orderNo}/status | 买家JWT | 14位订单号 | 状态、物流、商品名与数量 |
| POST /api/order/{orderNo}/pay | 买家JWT | 无body/query | null |
| POST /api/order/{orderNo}/ship | 本地管理 | logisticsCompany、trackingNo | null |
| POST /api/order/{orderNo}/cancel | 买家JWT | 无body/query | null |

成功HTTP200/code200/message“操作成功”；错误HTTP与code一致、data=null。JWT请求头是Authorization: 原始JWT，没有Bearer前缀。发货只接受common的本地管理门禁，不要求买家JWT；默认关闭403，显式dev、本机IPv4、开关及服务端密钥齐全后，缺失或错误请求密钥401。

六个买家接口每次都查数据库账号；不存在/已删401、禁用403。本人不存在、已删和他人的订单均404。客户端不能指定userId。

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

这是当前单实例方案。多实例只靠唯一索引和有限重试兜底，不承诺高吞吐；按日锁容器未作过期清理。创建没有请求幂等，两次相同请求可以生成两单，超时结果未知不能自动重放，应先核对本人订单。

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

普通mvn package默认跳过真实数据库测试。启用需要XIAOKE_ORDER_DB_TESTS=true、XIAOKE_ORDER_TEST_DB_URL，库名必须匹配xiaoke_order_verify_*；测试会清空这个专用库并注入故障，测试使用独立实例root/空口令，不得指向业务环境。当前机器完整复现脚本为output/run-order-verification.py，包含四模块真实数据库测试和三个实际jar联调。
