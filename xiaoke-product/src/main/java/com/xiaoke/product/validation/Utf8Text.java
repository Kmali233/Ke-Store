package com.xiaoke.product.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Utf8TextValidator.class)
public @interface Utf8Text {
    String message() default "描述超过 TEXT 的 65535 字节上限";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
