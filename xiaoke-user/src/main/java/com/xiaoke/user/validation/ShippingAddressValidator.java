package com.xiaoke.user.validation;

import com.xiaoke.user.dto.AddressInput;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ValidShippingAddress → 我负责处理哪种校验注解
 * AddressInput         → 我负责校验什么类型的对象
 */
public class ShippingAddressValidator
        implements ConstraintValidator<ValidShippingAddress, AddressInput> {
    @Override
    public boolean isValid(AddressInput value, ConstraintValidatorContext context) {
        if (value == null) { return true; }
        /**
         * Stream.of(...)              取出省、市、区、详细地址
         *     ↓
         * filter(Objects::nonNull)     去掉 null 项
         *     ↓
         * Collectors.joining(" ")      用一个空格连接各项
         *     ↓
         * length() <= 255              判断总长度是否符合要求
         */
        return Stream.of(value.getProvince(), value.getCity(), value.getDistrict(), value.getDetailAddress())
                .filter(Objects::nonNull).collect(Collectors.joining(" ")).length() <= 255;
    }
}
