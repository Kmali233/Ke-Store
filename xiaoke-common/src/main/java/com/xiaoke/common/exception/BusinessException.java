package com.xiaoke.common.exception;

import com.xiaoke.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常
 * <p>
 * 用于表达可预期的业务失败，由全局异常处理器统一转换为 {@code Result} 响应，
 * 且只记录状态码、不打印堆栈。异常携带 {@code code}，对应 {@link ResultCode} 中的状态码；
 * 仅 400/401/403/404/409/500/503 会原样透传，其余状态码统一降级为 500。
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务状态码，默认取 {@link ResultCode#FAIL} */
    private final int code;


    // 返回给前端的提示信息，状态码固定为:FAIL(500, "操作失败")
    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.FAIL.getCode();
    }


    // 使用预定义状态码，code 与提示信息均取自枚举，如:UNAUTHORIZED(401, "未认证")
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    // 自定义状态码与提示信息，用于枚举未覆盖的场景，如:409, "数据状态冲突"
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
