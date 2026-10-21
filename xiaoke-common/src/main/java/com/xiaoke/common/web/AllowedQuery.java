package com.xiaoke.common.web;

import java.lang.annotation.*;

/** REST 接口查询参数白名单；未标注的 REST 接口默认不接受查询参数。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AllowedQuery {
    String[] value();
}
