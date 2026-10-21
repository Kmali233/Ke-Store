package com.xiaoke.common.web;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.BeanUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

public class StrictInputPolicy implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)
                || !AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RestController.class)) {
            return true;
        }
        AllowedQuery annotation = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), AllowedQuery.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), AllowedQuery.class);
        }
        Set<String> allowed = annotation == null ? Set.of() : Set.copyOf(Arrays.asList(annotation.value()));
        for (var entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw invalid();
            }
            String value = entry.getValue()[0];
            Class<?> type = queryType(method, entry.getKey());
            if (isInteger(type) && (value == null || !value.matches("-?[0-9]+"))) {
                throw invalid();
            }
        }
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> values) {
            for (var parameter : method.getMethodParameters()) {
                var path = parameter.getParameterAnnotation(PathVariable.class);
                if (path != null && isInteger(parameter.getParameterType())) {
                    String name = !path.name().isEmpty() ? path.name()
                            : !path.value().isEmpty() ? path.value() : parameter.getParameter().getName();
                    Object value = values.get(name);
                    if (!(value instanceof String text) || !text.matches("[0-9]+")) {
                        throw invalid();
                    }
                    try {
                        if (Long.parseLong(text) <= 0) { throw invalid(); }
                    } catch (NumberFormatException ex) { throw invalid(); }
                }
            }
        }
        if (("GET".equals(request.getMethod()) || "DELETE".equals(request.getMethod()))
                && (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)) {
            throw invalid();
        }
        return true;
    }

    private static Class<?> queryType(HandlerMethod method, String name) {
        for (var parameter : method.getMethodParameters()) {
            var requestParam = parameter.getParameterAnnotation(RequestParam.class);
            if (requestParam != null) {
                String paramName = !requestParam.name().isEmpty() ? requestParam.name()
                        : !requestParam.value().isEmpty() ? requestParam.value() : parameter.getParameter().getName();
                if (name.equals(paramName)) { return parameter.getParameterType(); }
            } else if (!BeanUtils.isSimpleProperty(parameter.getParameterType())) {
                var descriptor = BeanUtils.getPropertyDescriptor(parameter.getParameterType(), name);
                if (descriptor != null) { return descriptor.getPropertyType(); }
            }
        }
        return String.class;
    }

    private static boolean isInteger(Class<?> type) {
        return type == Long.class || type == long.class || type == Integer.class || type == int.class;
    }

    private static BusinessException invalid() {
        return new BusinessException(ResultCode.PARAM_ERROR);
    }
}
