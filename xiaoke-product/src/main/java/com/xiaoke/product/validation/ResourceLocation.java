package com.xiaoke.product.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ResourceLocationValidator.class)   // 指定校验器
public @interface ResourceLocation {

    /**
     * 表示使用注解时不填写这个属性，就采用默认值
     */

    String message() default "图片仅支持 http/https URL 或相对路径";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
