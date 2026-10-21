package com.xiaoke.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component    // 表示这是需要 Spring 管理的组件
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-Id";


    /**
     * 流程：
     * 请求进来
     *     ↓
     * 服务端生成 traceId
     *     ↓
     * 放进日志上下文 MDC
     *     ↓
     * 通过响应头 X-Trace-Id 返回给客户端
     *     ↓
     * 请求结束时记录结果，并清理 MDC
     *
     * 假设一次修改商品失败了，响应头里有：X-Trace-Id: abc123...
     * 开发者就可以用这个编号搜索日志，找到同一次请求的相关记录。
     * */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        /** 给一次请求分配一个编号 */
        MDC.put("traceId", traceId);
        response.setHeader(HEADER, traceId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            // 路径模板避免把用户填入的单号、地址、token 或 query 写入日志。
            log.info("request traceId={} method={} route={} status={} elapsedMs={}", traceId,
                    request.getMethod(), route == null ? "<unmatched>" : route,
                    response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            MDC.remove("traceId");
        }
    }
}
