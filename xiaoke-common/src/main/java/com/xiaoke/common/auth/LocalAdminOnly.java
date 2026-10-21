package com.xiaoke.common.auth;

import java.lang.annotation.*;

/** 仅用于本机开发管理操作，不代表生产管理员角色。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LocalAdminOnly {
}
