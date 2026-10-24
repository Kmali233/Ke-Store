package com.xiaoke.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 商品服务启动类
 *
 * scanBasePackages 扫描本服务与 common，使 xiaoke-common 中的
 * 全局异常处理、跨域/分页等通用配置在本服务生效；
 * MapperScan 只加载商品服务自己的 Mapper。
 */
@SpringBootApplication(scanBasePackages = {"com.xiaoke.product", "com.xiaoke.common"})
@MapperScan("com.xiaoke.product.mapper")
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);   // 自动创建容器
    }
}
