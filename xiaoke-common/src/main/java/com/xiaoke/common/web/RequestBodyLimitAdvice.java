package com.xiaoke.common.web;

import com.xiaoke.common.config.CommonWebProperties;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/** 在 MVC 拦截器鉴权之后限制 JSON body；同时覆盖无 Content-Length 的流。 */
@RestControllerAdvice
public class RequestBodyLimitAdvice extends RequestBodyAdviceAdapter {
    private final CommonWebProperties properties;

    public RequestBodyLimitAdvice(CommonWebProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        long limit = properties.getMaxRequestBodyBytes();
        if (message.getHeaders().getContentLength() > limit) {
            throw new BodyTooLargeException();
        }
        InputStream bounded = new FilterInputStream(message.getBody()) {
            private long count;

            private void count(int read) {
                if (read > 0 && (count += read) > limit) { throw new BodyTooLargeException(); }
            }

            @Override
            public int read() throws IOException {
                int value = in.read();
                count(value < 0 ? 0 : 1);
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                int read = in.read(bytes, offset, (int) Math.min(length, limit - count + 1));
                count(read);
                return read;
            }
        };
        return new HttpInputMessage() {
            public HttpHeaders getHeaders() { return message.getHeaders(); }
            public InputStream getBody() { return bounded; }
        };
    }

    public static final class BodyTooLargeException extends RuntimeException {
        public BodyTooLargeException() { super("Request body too large"); }
    }
}
