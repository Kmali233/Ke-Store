package com.xiaoke.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.xiaoke.order", "com.xiaoke.common"})
@MapperScan({"com.xiaoke.order.mapper", "com.xiaoke.order.adapter.user", "com.xiaoke.order.adapter.address",
        "com.xiaoke.order.adapter.category", "com.xiaoke.order.adapter.product", "com.xiaoke.order.idempotency"})
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
