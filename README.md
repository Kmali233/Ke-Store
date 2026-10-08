# 小Ke商城（xiaoke-mall）

基于 Spring Boot 3.2 + MyBatis-Plus 的**多模块**商城后端。采用 Maven 聚合工程，每个服务独立进程、独立端口，可同时构建、启动多个 Services。

## 技术栈

- JDK 17、Spring Boot 3.2.5、Maven
- Spring Web、Spring Validation
- MyBatis-Plus 3.5.7、MySQL 8.x
- Lombok

## 模块结构

```
xiaoke-mall/                      # 父工程 (packaging=pom)：聚合子模块、统一依赖/插件版本
├── pom.xml
├── xiaoke-common/                # 公共模块（普通 jar，不启动）
│   └── src/main/java/com/xiaoke/common
│       ├── result                # Result 统一返回、ResultCode 状态码
│       ├── exception             # BusinessException、GlobalExceptionHandler
│       └── config                # CorsConfig(跨域)、MybatisPlusConfig(分页)
├── xiaoke-product/               # 商品服务（可独立启动，端口 8081）
│   └── src/main/java/com/xiaoke/product
│       ├── ProductApplication    # 启动类
│       ├── controller            # 控制层
│       ├── service / service/impl# 业务层
│       ├── mapper                # 持久层
│       ├── entity / dto / vo     # 实体 / 入参 / 出参
│       └── src/main/resources
│           ├── application.yml          # 服务配置(端口/MyBatis)
│           ├── application-dev.yml      # 开发环境数据源
│           └── application-prod.yml     # 生产环境数据源(环境变量注入)
├── sql/xiaoke_mall.sql           # 建库建表脚本
└── frontend/                     # 前端静态页
```

> 关键约定：所有服务启动类统一 `@SpringBootApplication(scanBasePackages = "com.xiaoke")` +
> `@MapperScan("com.xiaoke.**.mapper")`，因此 common 中的全局异常处理、跨域/分页配置对所有服务自动生效，新增服务无需任何额外配置。

## 快速开始

1. 创建数据库：执行 `sql/xiaoke_mall.sql`
2. 修改数据源：`xiaoke-product/src/main/resources/application-dev.yml` 中的账号密码
3. 启动商品服务：运行 `ProductApplication`，端口 `8081`，统一前缀 `/api`
4. 示例接口（商品）：
   - 分页：`GET /api/product/page?pageNum=1&pageSize=10`
   - 详情：`GET /api/product/{id}`
   - 新增：`POST /api/product`
   - 修改：`PUT /api/product`
   - 删除：`DELETE /api/product/{id}`

## 启动多个服务

- **IDEA**：Reload 父工程后，每个服务的 `*Application` 各生成一条运行配置，可分别启动、也可同时运行多个。
- **命令行**：
  - 启动单个服务：`mvn -pl xiaoke-product spring-boot:run`
  - 全部构建安装：`mvn clean install`
  - jar 启动：`java -jar xiaoke-product/target/xiaoke-product-1.0.0.jar`

## 新增一个服务（以「订单 order」为例）

1. 复制 `xiaoke-product` 目录，重命名为 `xiaoke-order`
2. 修改 `xiaoke-order/pom.xml` 的 `artifactId` 为 `xiaoke-order`
3. 将包名 `com.xiaoke.product` 整体改为 `com.xiaoke.order`，启动类改名为 `OrderApplication`
4. 修改 `application.yml`：`server.port` 改为 `8082`、`spring.application.name` 改为 `xiaoke-order`
5. 在父工程 `pom.xml` 的 `<modules>` 中追加一行 `<module>xiaoke-order</module>`
6. 启动 `OrderApplication` 即可

**端口规划**：网关预留 `8080`；业务服务从 `8081` 起（product=8081，order=8082，user=8083 …）。

各服务内新增业务对象时，统一用 `Result` 包装返回值，业务错误抛 `BusinessException`。
