package com.xiaoke.common.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明该接口需要买家身份。
 *
 * 身份的唯一来源是 Authorization 头里的 JWT —— 不接受客户端通过参数传 userId。
 */
@Target({ElementType.TYPE, ElementType.METHOD})   // 这个注解能贴在类上，也能贴在方法上
@Retention(RetentionPolicy.RUNTIME)   // 注解活到运行时，运行时可见，这样才能被 AOP 拦截到
@Documented   // 让 javadoc 也记录这个注解，做文档标记
public @interface LoginRequired {
}
