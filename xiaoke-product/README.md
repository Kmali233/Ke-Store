# 商品服务：当前接口契约与开发阅读顺序

更新日期：2026-10-23。服务端口 8081，统一前缀 /api。业务表是 t_product、t_category、t_product_attr；没有新增表或生产依赖。本文件对应当前源码，outputs/13 中的旧“现状”只代表设计时点。

## 1. 十二个接口

所有成功响应都是 HTTP 200、{"code":200,"message":"操作成功","data":...}。错误的 HTTP 状态与 code 相等，data=null。

| 方法与路径（省略 /api） | 权限 | 输入 | data |
|---|---|---|---|
| GET /product/page | 公开 | name?、categoryId?、pageNum=1、pageSize=10 | 分页 ProductVO |
| GET /product/{id} | 公开 | 正数 Long 路径 id | ProductDetailVO，带 attrs |
| GET /product/search | 公开 | keyword 必填，pageNum=1、pageSize=10 | 分页 ProductVO |
| POST /product | 本地管理 | ProductCreateDTO | null |
| PUT /product | 本地管理 | ProductUpdateDTO，body.id 必填 | null |
| DELETE /product/{id} | 本地管理 | 正数 Long 路径 id | null |
| GET /product/{id}/attrs | 公开 | 正数 Long 路径 id | ProductAttrVO 数组 |
| POST /product/{id}/attrs | 本地管理 | ProductAttrSaveDTO 数组，允许 [] 清空 | null |
| GET /category/list | 公开 | 无参数 | 扁平 CategoryVO 数组 |
| POST /category | 本地管理 | CategoryCreateDTO | null |
| PUT /category | 本地管理 | CategoryUpdateDTO，body.id 必填 | null |
| DELETE /category/{id} | 本地管理 | 正数 Long 路径 id | null |

公开 GET 忽略无效或过期 Authorization。七个写接口使用 common 的 @LocalAdminOnly，不使用买家 JWT 授予管理权限。管理默认关闭：403；显式 dev、IPv4 本机、服务端密钥齐全且开关开启后，缺失或错误请求密钥：401。

## 2. 输入与返回

商品新增必填 name（trim 后非空，最长128）、price（0–99999999.99，最多两位小数）、stock（非负 Integer）。可选 categoryId（正数）、image（最长255）、description（最多65535 UTF-8字节）、status（0/1，默认1）。

分类新增必填 name（trim 后非空，最长50）；parentId 默认0（根）、sort默认0（可负）、status默认1，icon最长255。修改需要正数id，其余字段可省略。

商品和分类 PUT 的 null/省略都保留原值；空 image/description/icon 清空字符串，空 name 不合法。categoryId=null 不清除现有分类，parentId=0 可以把分类移到根。只传id和同值更新成功。

图片只接受 http/https URL 或相对路径；拒绝 javascript/file/data 协议、协议相对地址和反斜线。服务端只保存字符串，不访问图片地址。description 原样保存，前端应以文本显示。

参数项必填 attrName（trim 后非空，最长50）、attrValue（trim 后非空，最长255）；attrGroup可空、trim空白转null、最长50；sort默认0、可负。整批校验通过后才开始替换。请求不接受参数id/productId。

商品VO固定字段：id、name、categoryId、price、stock、orderCount、image、description、status、createTime；详情增加attrs。参数VO：attrName、attrValue、attrGroup、sort。分类VO：id、name、parentId、icon、sort。空集合返回[]，时间是ISO本地时间。

**orderCount 是已创建且未取消的下单件数**，映射现有 sales 列。不是支付销量。商品新增为0，资料修改不能写它；以后由 order 下单增加、取消减少。

## 3. 核心业务规则

- 公开商品必须上架且未删除；有分类时，其分类和所有祖先都必须启用、未删除。NULL分类兼容可见。环、断链隐藏。
- 分类列表同样检查整条祖先链；按 parentId、sort、id 升序。停用祖先只影响公开可见性，不级联删除数据。
- 分页/搜索在 SQL 中过滤可见性，再计数和分页；固定 create_time DESC,id DESC。页大小1–100，超末页保持请求页号并返回空records。
- 搜索只针对商品名，trim后keyword必填；%/_/!按字面匹配，使用绑定参数及 ESCAPE '!'，反斜线不是转义符。
- 资料修改中的stock只是与锁定库存核对；不同返回409，整次请求回滚。更新SQL没有stock/sales字段。
- 分类自父引用400、循环409；新引用不存在404、停用链409。旧分类停用或历史断链时仍可维护资料、删除或移到健康分类。
- 有未删除子分类或商品时，分类删除409；下架商品也阻止删除。仅软删引用不阻止删除。
- 商品删除同时软删其有效参数，不改stock/sales。重复删除404。
- 参数覆盖只要求商品未删，允许维护下架商品；新集合全部插入成功才提交，任何失败恢复旧集合。

## 4. 事务与并发

CatalogTransactions 使用 Spring TransactionTemplate：写事务 READ_COMMITTED，公开组合读取只读 REPEATABLE_READ。复用 common DatabaseFailures，确认完整回滚后才将 MySQL 1205/1213 转409；连接失败503，提交结果不确定503，不自动重试。

分类锁协议：发现旧/新祖先链 → 合并去重 → 分类ID升序锁定 → 重查父级/状态/删除标记 → 再锁商品。链变化或商品分类变化返回409，不能持有商品锁再补分类锁。参数覆盖只锁商品，不再获取分类锁。相同资料不发 UPDATE；预期改变一行却返回0行时抛500并回滚。

公开详情/参数/分页在同一个数据库快照内完成组合查询。参数覆盖不会向读者暴露半套参数。

## 5. 从哪里开始读

先顺着一个请求走，暂时把事务组件当成“把里面的查询放在同一次读取中”。

1. ProductController.detail：看到 GET /product/{id} 如何进入 Java 方法。
2. ProductService.getProductById：了解接口声明。
3. ProductServiceImpl.getProductById：按顺序看查商品、检查可见性、组装参数和VO。
4. ProductMapper.findIncludingDeleted：对照 SELECT 和 t_product 字段。
5. Product / ProductVO / ProductDetailVO：区分数据库对象与返回对象。

然后依次阅读：分页和搜索 → 分类列表和祖先可见性 → 新增/修改商品 → 分类增改删 → 参数覆盖 → 锁和快照测试。

实际开发顺序：核对三张表和接口契约 → 实体/DTO/VO → Mapper → 分类链策略与事务入口 → 分类业务 → 商品查询与维护 → 参数业务 → Controller接线 → 单测/真实数据库/HTTP验收。

## 6. 启动

所有命令在仓库根目录执行：

~~~powershell
mvn -B -ntp -pl xiaoke-product -am package
java -jar xiaoke-product/target/xiaoke-product-1.0.0.jar
~~~

dev 数据库变量：DB_HOST（默认127.0.0.1）、DB_PORT（默认3306）、DB_NAME（默认xiaoke_mall）、DB_USERNAME、DB_PASSWORD。无默认凭据，.env不会自动加载。浏览商品不需要JWT密钥。

需要本机管理时，在 IDEA/启动进程设置：
- SPRING_PROFILES_ACTIVE=dev（显式激活）
- XIAOKE_LOCAL_ADMIN_ENABLED=true
- LOCAL_ADMIN_KEY=自己生成并妥善保存的密钥
- HTTP请求头 X-Local-Admin-Key 传同一密钥

仅默认dev配置而未显式激活dev时，管理门禁仍拒绝。prod使用DB_URL、DB_USERNAME、DB_PASSWORD；任何激活prod的情况均禁止本地管理写接口。

## 7. 验证与边界

普通构建：mvn -B -ntp package。真实 product 数据库测试需要 XIAOKE_PRODUCT_DB_TESTS=true 和 XIAOKE_PRODUCT_TEST_DB_URL；测试只允许 xiaoke_product_verify_* 专用库，建表、清空数据及故障注入均发生在这个库。

测试配置使用临时独立实例的root/空密码，仅为本机隔离测试；不要把这些测试凭据配置到业务环境。当前机器的完整复现入口是 output/run-product-verification.py，它创建独立 MySQL 实例与三个测试库，启用 common/user/product 数据库测试，随后验证打包jar的HTTP，最后清理临时数据。

order 模块尚未实现。库存并发测试用数据库事务模拟库存变化，不能等同于下单/支付/取消接口联调。商品服务不包含补货专用接口、上传、分类树接口或生产RBAC。
