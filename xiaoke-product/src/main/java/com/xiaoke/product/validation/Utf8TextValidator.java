package com.xiaoke.product.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

/**
 * 限制描述文本编码后的字节数，16位无符号整数最大值为65535
 */
public class Utf8TextValidator implements ConstraintValidator<Utf8Text, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= 65535;
    }
}
