package com.xiaoke.common.web;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 业务查询 DTO 可继承；入口 @Valid 之后仍可调用 validatePage 保护内部调用。 */
@Getter
@Setter
public class PageQuery {
    @NotNull @Min(1)
    private Long pageNum = 1L;
    @NotNull @Min(1) @Max(100)
    private Long pageSize = 10L;


    /** 进行显式检查，包括分页偏移量的溢出检查 */
    public void validatePage() {
        if (pageNum == null || pageSize == null || pageNum < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        try {
            Math.multiplyExact(pageNum - 1, pageSize);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
    }
}
