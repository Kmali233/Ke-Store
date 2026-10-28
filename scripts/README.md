# 最终系统测试与并发验证

阶段04完整验收：`python scripts/verify-admin-auth.py`。当前针对独立 `xiaoke-admin` 模块，继承阶段01—03迁移、幂等、SKU和交易并发回归。构建并核对common/product/user/order/admin五个模块，启动独立admin、user、product和双order共五个JVM，验证管理员入口只属于admin、离线初始化与重复拒绝、买家/后台隔离、24并发退出只递增一次版本、禁用/删除/恢复、断库503、恢复就绪和日志凭据。product/order真实DB测试直接验证本地只读适配器和共享认证规则，不向生产JAR添加测试身份入口。操作说明见[后台认证实现说明](../xiaoke-admin/详细实现说明.md)。

阶段04也支持 `python scripts/verify-admin-auth.py --http-only`：明确复用此前已验证的四个业务JAR，在新的隔离MySQL中用Flyway迁移全部版本后，执行全部HTTP、并发、账本、故障及日志检查。此模式不重跑Maven/JUnit或历史迁移演示；报告mode为 `full_http_using_existing_verified_jars`，`maven_executed=false`，不能冒充重新clean verify。用于Java测试和迁移已通过后，单独复验HTTP验证器。数据库重启先等待真实关闭完成，再观察五个JVM的连接池恢复就绪。重构后首次验证必须执行默认完整入口，以清理旧class和重新核对模块归属。

阶段03完整验收：`python scripts/verify-skus.py`。继承阶段01/02回归，增加V2→V3默认SKU回填中断续跑与原账核对、实时计数不重置、双order JVM规格竞争/兄弟SKU库存隔离、规格快照与软删恢复、旧明细/旧幂等契约回归。结果仍要求completed及两项临时资源清理均为true。

本地选购页行为：`node scripts/verify-sku-page.cjs`，以最小DOM和受控网络结果执行真实页面脚本，验证规格选择、售罄禁用、未知结果下保留同一键/请求、404不丢键及明确开始新订单。它不等于浏览器视觉/CORS端到端验收。`node --check xiaoke-product/src/main/resources/static/sku-demo.js`检查语法；没有新增包或生产依赖。

阶段02完整验收入口：`python scripts/verify-idempotency.py`。它包含阶段01迁移回归及全部系统验收，并增加同键24并发、同键异参竞争、跨用户隔离、SQL故障整笔回滚、真实order进程重启、成功响应丢弃后重发、商品服务停止时重放和全库回执一致性检查。JUnit也覆盖模拟提交确认丢失；它不是网络层commit丢包实验。

阶段02可用 `python scripts/verify-idempotency.py --faults-only --workers 4 --rounds 1` 复测实际故障段，复用已有通过验证的JAR，不重跑Maven；仍包含父类迁移回归和测试结构核对，结果不应冒充重新完成全部并发验收。

测试结构生成入口：`python scripts/sync-test-schemas.py`，`--check`只核对。它需要MySQL8二进制，在随机端口隔离实例回放全部版本SQL后读取SHOW CREATE TABLE，不连接业务库；现支持整数版本、普通可由mysql客户端执行的SQL迁移。复杂DELIMITER/数据依赖迁移需继续扩展生成器。正式迁移与历史校验由Flyway负责。

阶段01新增数据库迁移验收入口：`python scripts/verify-migrations.py`。它先验证空库迁移、旧库备份接管、结构差异拒绝、增量加字段、校验和失败、备份恢复及部分DDL失败恢复，再执行下述完整系统并发验证；真实HTTP服务使用Flyway创建的库。正常迁移操作见 [sql/README.md](../sql/README.md)，入口是 `python scripts/db_migrate.py`。

在仓库根目录运行：

~~~powershell
python scripts/verify-system.py
~~~

使用Python标准库、项目现有Java/Maven，以及本机MySQL8二进制。默认MySQL目录为D:/Develop/mysql-8.0.34-winx64，可覆盖：

~~~powershell
python scripts/verify-system.py --mysql-home 'D:/Develop/mysql-8.0.34-winx64' --workers 24 --rounds 8
~~~

脚本只创建独立临时MySQL实例、随机端口与专用测试库，不连接现有3306业务服务，不执行商城DROP初始化脚本。它运行根mvn -B -ntp clean verify并启用全部真实数据库测试，再启动user、product和两个独立order JAR执行实际HTTP并发。

包含同名注册、默认地址、抢库存、重复支付/取消、发货对取消、商品资料对下单、分类引用/环、参数覆盖/读取/删除、地址修改/删除对下单、不同商品跨order进程并发单号，以及提交后响应被测试代理丢弃的场景。

结束前逐商品核对“stock=初始stock-未取消订单数量”和“sales=未取消订单数量”，检查金额、孤儿记录、默认地址唯一性、分类环、日志凭据和临时诊断标记。最后停止临时product和MySQL，确认HTTP503及下单失败零写入。所有临时进程和数据在finally中清理。

测试用独立实例root/空密码只存在于临时环境；业务配置不使用这些测试凭据。脚本启动JAR时设置连接池获取超时2秒，用于在故障注入时及时验证503；这不是修改业务配置。

结果保存在output/final-verification/时间戳-随机后缀/：

- result.json：构建、场景、HTTP数量、状态分布、清理结果。
- test-cases.json：本轮Surefire测试名。
- http-checks.json：请求方法、路径、HTTP、traceId与耗时，不保存请求体或凭据。
- maven-clean-verify.log及各服务日志。

只有result.json中completed、temporary_processes_stopped、temporary_data_removed都为true，才算本轮完整结束。HTTP400/401/404/409/503中包含预期拒绝和故障注入结果，判断以场景断言为准。

可只复测真实服务故障（使用已有打包JAR，不运行Maven）：

~~~powershell
python scripts/verify-system.py --faults-only --workers 4 --rounds 1
~~~

24并发与两个同机order进程用于验证正确性，不代表生产容量或多机部署验收。响应丢失测试通过本机测试代理，在上游已返回成功后丢弃客户端响应；它验证“调用方未收到结果也可能已经提交”，不等同于覆盖所有网络故障。阶段02同时验证无键旧调用可建多单、带键重发只返回原订单。

最终报告与90组设计证据索引保存在本地outputs目录。output/outputs被Git忽略，提交时仍应审核暂存内容。
