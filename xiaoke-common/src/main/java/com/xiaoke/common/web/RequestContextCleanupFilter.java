package com.xiaoke.common.web;

import com.xiaoke.common.auth.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 最外层请求上下文清理。
 *
 * 排序取 HIGHEST_PRECEDENCE，保证它是最外层：
 * 入栈时先清一次（上一个请求可能在同一线程留下身份），
 * 出栈时 finally 再清一次 —— 覆盖「正常返回 / 被拒绝 / 抛异常」三条路径。
 *
 * 这里刻意不做认证：Servlet 过滤器抛出的异常不会自动进入 @RestControllerAdvice，
 * 在过滤器里认证会造成两套错误格式。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextCleanupFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UserContext.clear();
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
