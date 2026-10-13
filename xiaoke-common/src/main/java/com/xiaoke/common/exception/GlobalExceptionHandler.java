package com.xiaoke.common.exception;

import com.xiaoke.common.result.Result;
import com.xiaoke.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Set;

/**
 * 全局异常处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** BusinessException 允许映射成 HTTP 状态的码；白名单之外一律降级 500 */
    private static final Set<Integer> ALLOWED_BUSINESS_CODES = Set.of(400, 401, 403, 404, 409, 500, 503);

    /** 业务码表之外、仅存在于协议层的 Web 边界状态 */
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int PAYLOAD_TOO_LARGE = 413;
    private static final int UNSUPPORTED_MEDIA_TYPE = 415;

    /** 唯一出口：HTTP 状态码 == body.code */
    private static ResponseEntity<Result<Void>> build(int code, String message) {
        return ResponseEntity.status(code).body(Result.fail(code, message));
    }


    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        int raw = e.getCode();
        if (!ALLOWED_BUSINESS_CODES.contains(raw)) {
            // 不拿任意码去 HttpStatus.valueOf()，否则会抛 IllegalArgumentException
            log.error("业务异常携带未登记的 code={}，已降级 500", raw);
            return build(ResultCode.FAIL.getCode(), ResultCode.FAIL.getMessage());
        }
        log.warn("业务异常: code={}, message={}", raw, e.getMessage());
        return build(raw, e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Result<Void>> handleValidException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message;
        if (fieldError == null) {
            message = ResultCode.PARAM_ERROR.getMessage();
        } else if (fieldError.isBindingFailure()) {
            // 类型/格式转换失败：不回显原始值与 Java 类型，只给稳定文案
            message = "参数 " + fieldError.getField() + " 格式不正确";
        } else {
            message = fieldError.getField() + " " + fieldError.getDefaultMessage();
        }
        log.warn("参数校验失败: {}", message);
        return build(ResultCode.PARAM_ERROR.getCode(), message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getClass().getSimpleName());
        return build(ResultCode.PARAM_ERROR.getCode(), "请求体格式不正确");
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> handleNoResource(Exception e) {
        return build(ResultCode.NOT_FOUND.getCode(), ResultCode.NOT_FOUND.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return build(METHOD_NOT_ALLOWED, "请求方法不支持");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return build(UNSUPPORTED_MEDIA_TYPE, "媒体类型不支持");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return build(PAYLOAD_TOO_LARGE, "请求体过大");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return build(ResultCode.FAIL.getCode(), ResultCode.FAIL.getMessage());
    }
}
