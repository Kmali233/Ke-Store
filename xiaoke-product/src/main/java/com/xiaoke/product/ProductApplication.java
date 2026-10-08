package com.xiaoke.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 商品服务启动类
 *
 * scanBasePackages 扫描整个 com.xiaoke，使 xiaoke-common 中的
 * 全局异常处理、跨域/分页等通用配置在本服务生效；
 * MapperScan 用通配符匹配各服务自己的 mapper 包，新增服务无需改动。
 */
@SpringBootApplication(scanBasePackages = "com.xiaoke")
@MapperScan("com.xiaoke.**.mapper")
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
