package com.xiaoke.product;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 不依赖数据库/容器的基础测试。
 * 需要联调时再使用 @SpringBootTest。
 */
class ProductApplicationTests {

    @Test
    void contextInfo() {
        assertNotNull(ProductApplication.class);
    }
}
