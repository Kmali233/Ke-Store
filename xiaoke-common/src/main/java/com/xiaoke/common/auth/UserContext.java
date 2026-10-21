package com.xiaoke.common.auth;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;

/**
 * 请求线程内的身份上下文。
 *
 * 只回答"这一次请求是谁"，不做账号状态判断（那是各业务模块自己的事）。
 * 刻意不用 InheritableThreadLocal：本期控制器同步处理，身份不跨线程传递；
 * 可继承版本会让异步任务悄悄继承一个已经失效的身份，反而更危险。
 */
public final class UserContext {

    private static final ThreadLocal<AuthPrincipal> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(AuthPrincipal principal) {
        HOLDER.set(principal);  // 设置当前线程的身份
    }

    /** 取当前身份；没有身份时抛 401，绝不返回 null 让调用方自行猜测 */
    public static AuthPrincipal requirePrincipal() {
        AuthPrincipal principal = HOLDER.get();
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return principal;
    }

    /** 读取用户 ID */
    public static Long requireUserId() {
        return requirePrincipal().userId();
    }

    /**
     * 清空。用 remove() 而不是 set(null)：后者会在当前线程的 ThreadLocalMap 里
     * 留下一个 value 为 null 的 Entry，长期运行会累积成内存泄漏。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
