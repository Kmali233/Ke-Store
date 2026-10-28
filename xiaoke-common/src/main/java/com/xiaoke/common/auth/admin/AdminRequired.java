package com.xiaoke.common.auth.admin;

import java.lang.annotation.*;

/** 标记需要管理员认证的入口 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminRequired { }
