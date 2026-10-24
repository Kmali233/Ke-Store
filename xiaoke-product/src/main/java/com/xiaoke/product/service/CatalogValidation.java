package com.xiaoke.product.service;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** HTTP 和直接调用 Service 共用的参数边界。 */
@Component
@RequiredArgsConstructor
public class CatalogValidation {
    private final Validator validator;
    public void check(Object dto) {
        if (dto == null || !validator.validate(dto).isEmpty()) { throw new BusinessException(ResultCode.PARAM_ERROR); }
    }
    public void id(Long id) {
        if (id == null || id <= 0) { throw new BusinessException(ResultCode.PARAM_ERROR); }
    }
    public static void oneRow(int count) {
        if (count != 1) { throw new BusinessException(ResultCode.FAIL); }
    }
}
