package com.xiaoke.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/** 与订单快照列宽一致，省市区和详细地址以单空格连接后至多 255。 */
@Target(ElementType.TYPE)       // 这个注解标在类上，检查整个对象
@Retention(RetentionPolicy.RUNTIME)   // 程序运行时，框架能读取这个注解
@Inherited
@Constraint(validatedBy = ShippingAddressValidator.class)  // 遇到 @ValidShippingAddress 时，由 ShippingAddressValidator 执行校验
public @interface ValidShippingAddress {
    String message() default "完整地址最多255位";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
