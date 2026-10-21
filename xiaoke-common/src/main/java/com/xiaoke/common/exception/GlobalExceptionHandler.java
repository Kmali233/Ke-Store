package com.xiaoke.common.exception;

import com.xiaoke.common.result.Result;
import com.xiaoke.common.result.ResultCode;
import com.xiaoke.common.web.RequestBodyLimitAdvice.BodyTooLargeException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Comparator;
import java.util.Set;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Set<Integer> ALLOWED_BUSINESS_CODES = Set.of(400, 401, 403, 404, 409, 500, 503);

    private static ResponseEntity<Result<Void>> build(int code, String message) {
        return ResponseEntity.status(code).body(Result.fail(code, message));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        if (!ALLOWED_BUSINESS_CODES.contains(ex.getCode())) {
            return build(500, ResultCode.FAIL.getMessage());
        }
        // Business messages may contain input: log the code only.
        log.warn("business_failure code={}", ex.getCode());
        String message = switch (ex.getCode()) {
            case 500 -> ResultCode.FAIL.getMessage();
            case 503 -> ResultCode.SERVICE_UNAVAILABLE.getMessage();
            default -> ex.getMessage();
        };
        /** 最终通过 build(...) 返回 HTTP 401 和响应体
         * {
         *   "code": 401,
         *   "message": "未认证",
         *   "data": null
         * }
         * */
        return build(ex.getCode(), message);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleValidException(BindException ex) {
        FieldError error = ex.getBindingResult().getFieldErrors().stream()
                .min(Comparator.comparing(FieldError::getField)).orElse(null);
        return build(400, error == null ? "参数错误" : "参数 " + error.getField() + " 不合法");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        if (DatabaseFailures.causes(ex).stream().anyMatch(BodyTooLargeException.class::isInstance)) {
            return build(413, "请求体过大");
        }
        return build(400, "请求体格式不正确");
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, ServletRequestBindingException.class})
    public ResponseEntity<Result<Void>> handleInput(Exception ex) {
        return build(400, "参数错误");
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> handleNoResource(Exception ex) {
        return build(404, ResultCode.NOT_FOUND.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(405).headers(ex.getHeaders()).body(Result.fail(405, "请求方法不支持"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(415).headers(ex.getHeaders()).body(Result.fail(415, "媒体类型不支持"));
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, BodyTooLargeException.class})
    public ResponseEntity<Result<Void>> handleMaxUploadSize(Exception ex) {
        return build(413, "请求体过大");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        boolean unknown = DatabaseFailures.outcomeUnknown(ex);
        // No Throwable argument: exception messages can contain SQL, tokens, passwords or addresses.
        log.error("internal_failure type={} outcomeUnknown={} location={}", ex.getClass().getSimpleName(),
                unknown, ex.getStackTrace().length == 0 ? "unknown" : ex.getStackTrace()[0]);
        if (unknown) {
            return build(503, "操作结果待核对，请勿自动重试");
        }
        if (DatabaseFailures.connectionUnavailable(ex)) {
            return build(503, ResultCode.SERVICE_UNAVAILABLE.getMessage());
        }
        return build(500, ResultCode.FAIL.getMessage());
    }
}
