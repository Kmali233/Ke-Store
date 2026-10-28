# 数据库增量迁移（阶段01）

## 阶段04：V4后台独立账号（2026-10-28）

`V4__admin_auth.sql`新增 `t_admin_account`，包括独立用户名唯一索引、BCrypt哈希、启停/删除状态、非负 `token_version` 及时间列。没有默认管理员或演示口令。V1—V3保持原文；当前全量迁移产生11张业务/迁移辅助表（不含Flyway history）。下文各阶段表数是历史记录。

按既有迁移流程核对目标、备份并在维护期间运行 `python scripts/db_migrate.py migrate --maintenance`，随后执行 `python scripts/db_migrate.py validate`；既有V3库只新增V4表，原买家/商品/订单数据不重写。2026-10-29已将账号维护重写为独立 `xiaoke-admin` 模块，先迁移再使用admin JAR离线初始化账号，然后启动admin。具体命令见[后台认证实现说明](../xiaoke-admin/详细实现说明.md)。V4表结构不因模块迁移而改写，不需要V5；此前已迁移的库保留账号、哈希和版本。恢复旧版代码不会删除该表；回滚数据库仍按备份恢复流程处理，禁止修改已执行脚本。

`sync-test-schemas.py`为admin生成独立后台表测试结构，user结构仅有买家和地址表；product/order结构含后台表以验证本模块只读认证适配器。`python scripts/verify-admin-auth.py`核对四个业务模块的测试结构，以及实际JAR连接的完整迁移库。

## 阶段03：V3与默认SKU切换（2026-10-28）

`V3__product_sku.sql`新增t_product_sku、t_catalog_migration，并为t_order_item增加可空的sku_id/sku_name/sku_spec及索引。V1/V2原文不变，历史订单不回填当前规格、不重算金额，旧幂等回执不改写。

必须停止全部旧product/order写进程和其他写入者，核对目标、备份后运行 `python scripts/db_migrate.py migrate --maintenance`。入口在Flyway成功后调用sku_backfill.py，每批最多200个商品复制旧price/stock/sales到唯一默认SKU，覆盖上架、下架和软删商品；每批独立提交，已经存在的默认SKU不覆盖。完成前比较原商品与默认SKU账本；成功写入t_catalog_migration(version=3)，然后 `python scripts/db_migrate.py validate`，最后统一启动新版服务。

分批中断时Flyway的V3可能已经成功，但完成标记尚不存在：保持停写，用同一命令继续回填。已有SKU与旧值不一致时拒绝标记完成，不自动覆盖。完成后再次migrate只检查默认映射存在，不将冻结SPU计数复制回SKU。不要只看Flyway history成功便启动业务；validate还会检查回填完成标记及默认SKU缺失/被删。

新交易唯一库存权威为t_product_sku。t_product的price/stock/sales冻结保留用于迁移核验，旧二进制继续写这些列会产生两套账，故本阶段不支持旧新写进程混合的滚动发布。代码回退须继续停写并评估切换方案，不能简单重启旧二进制；上线已有新交易后，恢复发布前备份会丢失新交易，不能直接覆盖。

空库最终有10张业务/迁移辅助表（不含Flyway history）；迁移脚本不注入演示商品。测试结构生成器回放V1–V3的DDL；真实旧数据分批回填、故障恢复和账本核对由 `python scripts/verify-skus.py` 单独验证。下文阶段01/02表数是其历史时点说明，当前部署以本节为准。

阶段02新增 `V2__order_idempotency.sql`：在原七张业务表外增加 `t_order_idempotency` 成功下单回执表，联合主键 `(user_id,idempotency_key)` 区分用户及键，键采用ascii_bin区分大小写。保存摘要版本、SHA-256摘要、首次orderNo/status/totalAmount；没有TTL和删除任务，不改写V1。新环境migrate会应用V1、V2；已有V1库经校验及备份后仅应用V2。必须先迁移后部署新order；未知提交结果和键生命周期见 [订单接口契约](../xiaoke-order/README.md)。

从商城仓库根目录运行所有命令。统一入口为 `python scripts/db_migrate.py`，底层使用根 POM 的 `db-migration` profile，通过 `mvn -N -Pdb-migration ... flyway:<goal>` 显式调用 Flyway 13.11.1。插件及其 MySQL 支持只属于迁移工具，不进入业务服务的运行时依赖，也没有绑定服务启动或 Maven 默认构建阶段。

## 1. 数据和版本边界

- `sql/migration/V1__initial_schema.sql`：当前七张业务表的结构基线；不包含 DROP、CREATE DATABASE、USE、示例账号或演示数据。
- `sql/schema-v1.json`：由隔离 MySQL 8.0.34 的 SHOW CREATE TABLE 获取的结构快照，以及 V1 文本的 SHA-256。文本摘要统一换行，兼容 Windows Git CRLF。
- 统一采用 InnoDB、utf8mb4、utf8mb4_0900_ai_ci；保留原字段、索引和注释。原脚本的 CREATE DATABASE 指定 general_ci，但每张表只指定 CHARSET=utf8mb4，在本机 MySQL 8.0.34 实际得到的表排序规则是 0900_ai_ci。V1 显式锁定实测结构，不把数据库默认值误当成表的实际值。已有表若为 general_ci，严格接管会拒绝，不自动转换。主键当前实际为有符号 BIGINT，不是旧设计规范中的 UNSIGNED；`sales` 仍表示已创建且未取消的下单件数；签收时间现有列名为 `finish_time`。
- V1 没有外键：实体之间的关系由当前业务代码维护。阶段01不新增业务约束或更改 HTTP 接口。
- Flyway 另建 `flyway_schema_history`，记录版本、脚本名、校验和、时间和成功状态；它不是业务表。
- `sql/xiaoke_mall.sql` 仅保留为历史初始化参考，含 DROP 和旧演示账号。新环境及已有库均改走统一迁移入口。

| 表 | 关键索引（主键均为 id） |
|---|---|
| t_user | uk_username、idx_phone |
| t_address | idx_user_id |
| t_category | idx_parent_id |
| t_product | idx_category_id、idx_name、idx_status |
| t_product_attr | idx_product_id |
| t_orders | uk_order_no、idx_user_id、idx_status、idx_create_time |
| t_order_item | idx_order_id、idx_order_no、idx_product_id |

## 2. 环境和目标库

需要 Java 17、Maven 3、Python 3.11+ 和 MySQL 8 客户端工具（mysql、mysqldump）。首次使用 Maven 需要能下载已锁定的 Flyway 插件及其依赖。MySQL 8.0.34 是本阶段实际验证版本；其他服务器版本可能产生不同的 SHOW CREATE 格式，严格校验不一致时先人工核对，不自动放宽接管。

迁移用户须能完整查看目标库表/视图/触发器/例程/事件，进行所需 DDL、管理 history 表并完成 mysqldump。不要用权限不足的账号证明“没有其他对象”。

入口不读取业务应用的 DB_* 配置，不默认连接 3306，不自动创建数据库。必须显式提供以下五个变量（允许空测试密码，但变量必须存在）：

```powershell
$env:XIAOKE_MYSQL_HOME = 'D:/Develop/mysql-8.0.34-winx64'
$env:XIAOKE_MIGRATION_HOST = '127.0.0.1'
$env:XIAOKE_MIGRATION_PORT = '3306'       # 核对目标实例；验收脚本使用随机临时端口
$env:XIAOKE_MIGRATION_DATABASE = 'xiaoke_mall'  # 核对目标库，须由操作者预先创建
$env:XIAOKE_MIGRATION_USER = '你的迁移账号'
$migrationSecret = Read-Host '目标数据库密码' -AsSecureString
$env:XIAOKE_MIGRATION_PASSWORD = [System.Net.NetworkCredential]::new('', $migrationSecret).Password
```

密码只传入子进程环境，不出现在命令参数中；不要把它写入 SQL、POM 或执行记录。工具通过 `--no-defaults` 与隔离登录文件路径防止读入本机的 mysql 登录配置；所有数据库连接参数由上述变量显式指定。迁移器仅支持普通主机名/IPv4和字母数字下划线库名；不支持直接传任意 JDBC URL或 IPv6。

当前 JDBC 参数沿用本机开发方式（useSSL=false），本阶段仅实测本地连接。远程迁移须先建立受保护的连接并按环境完善 TLS 配置，不能把本地验收视为远程安全配置验收。

完成操作后清除本轮密码变量：

```powershell
Remove-Item Env:XIAOKE_MIGRATION_PASSWORD
Remove-Variable migrationSecret
```

## 3. 空库初始化

1. 由操作者在正确实例预建独立空库，停止指向该库的业务服务和所有写入者。
2. 配置上述环境；`--maintenance` 表示你已经停止业务写入与其他 DDL 操作者。此参数是操作声明，脚本不会替你停服。
3. 运行迁移，再检查状态：

新环境预建数据库的SQL如下，在已经核对目标实例的MySQL客户端执行；如果目标库已存在，应先判断属于空库还是旧库分支，不删除重建：

```sql
CREATE DATABASE xiaoke_mall CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

```powershell
python scripts/db_migrate.py migrate --maintenance
python scripts/db_migrate.py info
python scripts/db_migrate.py validate
```

当前结果：创建八张空业务表和history表，V1、V2状态成功。不会创建默认管理员/买家，也不会导入演示商品。`check-v1`只用于尚未应用V2的旧库结构核对，当前版本空库迁移后运行`info`和`validate`，不再运行`check-v1`。验证后再按既有说明启动product、user、order。

## 4. 已有库显式接管

对历史库先停写、核对实例/库名、确认备份存放位置与访问权限，再运行：

```powershell
python scripts/db_migrate.py check-v1
python scripts/db_migrate.py baseline --maintenance
python scripts/db_migrate.py info
python scripts/db_migrate.py migrate --maintenance
python scripts/db_migrate.py validate
```

`baseline` 的执行顺序：

1. 确认目标库尚无 history 表。
2. 与 V1 快照比较全部业务表及其 SHOW CREATE 结构。比较列类型、顺序、可空性、默认值、注释、索引字段/顺序/唯一性、表引擎和排序规则；忽略数据导致的表级自增计数和索引声明顺序。
3. 检查额外表/视图/触发器/例程/事件。任何不匹配均拒绝接管；不擅自改成“差不多”的结构。
4. 使用 mysqldump 自动备份，写入 SHA-256 与结构回执；失败直接停止。
5. 再次比较结构，然后显式执行 Flyway baseline，记录基线版本1，不重跑 V1 建表，不插入或清空业务数据。

非空且没有 history 的库直接执行 migrate 会被拒绝。插件同时关闭 baselineOnMigrate；未知旧库不能自动接管。已经存在 history 的库不允许再次 baseline。

实际已有商城业务库在本阶段没有接管或修改；完整流程已在含测试数据的独立历史结构库上验证。

## 5. 后续加表、改表

新增 `sql/migration/V2__明确说明.sql`，之后版本继续递增。已执行的文件不再修改/改名/删除；修正已交付结构时增加新版本。

```powershell
python scripts/db_migrate.py migrate --maintenance
python scripts/db_migrate.py validate
```

已有 history 时，入口先校验已执行脚本（预检只允许新版本处于 pending，不忽略缺失、校验差异或比本地更高的版本），自动备份，再执行 migrate；Flyway 在迁移时再次校验并使用自身 history 锁。未应用的新版本会正常执行；独立 validate 仍要求没有 pending 版本。

目前的 `check-v1` 只用于 V1 接管或 V1结构确认，未来新增业务字段后不应再期待它通过。Flyway validate 检查迁移历史与文件，不等于自动检测所有人工改表；维护期间禁止绕过迁移入口写 DDL。

阶段02已扩展测试库生成器：在隔离MySQL回放V1、V2全部版本，按模块提取实际SHOW CREATE结构生成测试SQL；`--check`检查已提交结构文件是否一致。需要本机MySQL8二进制，默认路径及覆盖方式见scripts/README.md。新增版本后仍须同步生成并核对，不能只改正式迁移。

## 6. 备份、恢复及失败处理

手工备份：

```powershell
python scripts/db_migrate.py backup --maintenance
```

备份保存于 `output/database-backups/<库名>-<时间>-<随机值>.sql`，配套同名 `.json` 保存源实例/库名、SQL SHA-256 和结构快照。备份包含数据与密码哈希等业务敏感内容，只保存在受控本地目录，不提交或上传。该目录被 Git 忽略，但忽略规则不能代替权限控制或提交审计。

备份使用 single-transaction、routines、events、triggers、hex-blob，且不含 CREATE DATABASE/USE。备份前后检查结构一致；仍必须停写，single-transaction 不能替代 DDL 维护窗口。通过实际恢复并核对业务行才能证明备份可用。

恢复时预建一个新的空库，将环境变量中的 DATABASE 改为该库，保持业务停写：

```sql
CREATE DATABASE xiaoke_restore_review CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

```powershell
$env:XIAOKE_MIGRATION_DATABASE = 'xiaoke_restore_review'
python scripts/db_migrate.py restore --maintenance --backup-file 'output/database-backups/实际备份文件.sql'
python scripts/db_migrate.py info
```

恢复器要求配套回执、验证 SHA-256，只接受无任何对象的空目标库。执行后比较结构；已非空的目标拒绝覆盖。恢复后还须核对业务数据与 history，再决定服务连接切换。基线前备份不含 history，恢复后要重新 check-v1 → baseline；已迁移库的备份包含 history，恢复后用匹配版本的迁移文件 validate。

| 失败 | 行为及处置 |
|---|---|
| 连接/权限/目标不存在 | 停止；检查显式目标及权限，不创建未知数据库 |
| 旧库结构不符 | 不写 baseline；人工核对差异并制定有备份的修复方案 |
| 备份失败 | 不继续 baseline 或已有库迁移；不把残留 SQL 当作有效备份 |
| 已执行脚本校验不同 | 停止；找回原脚本或新增修复版本，不能用 repair 掩盖改动 |
| MySQL DDL 中途失败 | 某些 DDL 已隐式提交，可能存在部分变更；停止写入并保留失败 history/日志 |
| 恢复失败 | 目标可能部分恢复；保留现场，换新的空库重新核对，不能在半恢复库盲目继续 |

入口不开放 clean/自动 repair/自动删表/自动回退。MySQL DDL 不能依赖事务整体回滚；优先把已核验备份恢复到新空库，核对后切换。必须向前修复时，先审查实际结构与失败 history，再人工制定修复步骤。当前方案要求单一迁移操作者；Flyway 锁不覆盖入口的备份/结构检查阶段，`--maintenance` 不提供跨主机业务停写保证。

## 7. 复现本阶段验收

```powershell
python scripts/sync-test-schemas.py --check
python scripts/verify-migrations.py
```

第二条复用 `verify-system.py` 的隔离实例、构建、HTTP 并发和 finally 清理能力，额外覆盖迁移/接管/结构差异/备份恢复/部分 DDL 失败。它自行设置临时连接配置，不使用你的迁移目标环境，也不连接原业务3306端口。

阶段01验收中的V2加字段/失败演示脚本只生成在`output/final-verification/.../`的独立迁移目录，与正式V2隔离；JUnit使用全部正式版本回放生成的测试脚本，真实HTTP服务使用Flyway应用当前全部版本的新库。阶段02运行`python scripts/verify-idempotency.py`。报告分别记录迁移场景、JUnit、HTTP断言、进程退出和临时数据清理情况；以实际报告为准。

## 8. 参考

- [Flyway Maven 插件与显式命令](https://documentation.red-gate.com/flyway/reference/usage/maven-goal)
- [MySQL 支持模块](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)
- [baseline 的含义](https://documentation.red-gate.com/flyway/reference/commands/baseline)
- [validate 检查范围](https://documentation.red-gate.com/flyway/reference/commands/validate)
- [预检 pending 过滤规则](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-ignore-migration-patterns-setting)
