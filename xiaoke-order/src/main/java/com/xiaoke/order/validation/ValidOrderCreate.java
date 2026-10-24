package com.xiaoke.order.validation;
import jakarta.validation.*;
import java.lang.annotation.*;

/**
 * 定义“创建订单需要满足这些规则”的注解
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy=OrderCreateValidator.class)
public @interface ValidOrderCreate {
    String message() default "收货来源或商品列表不合法";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
