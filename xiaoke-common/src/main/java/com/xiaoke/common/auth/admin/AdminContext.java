package com.xiaoke.common.auth.admin;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;

/** Administrator and buyer identities deliberately use separate holders. */
public final class AdminContext {
    private static final ThreadLocal<AdminPrincipal> HOLDER = new ThreadLocal<>();
    private AdminContext() { }
    public static void set(AdminPrincipal principal) { HOLDER.set(principal); }
    public static AdminPrincipal requirePrincipal() {
        var principal = HOLDER.get();
        if (principal == null) { throw new BusinessException(ResultCode.UNAUTHORIZED); }
        return principal;
    }
    public static void clear() { HOLDER.remove(); }
}
