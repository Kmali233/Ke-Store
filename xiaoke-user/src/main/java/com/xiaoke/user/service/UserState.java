package com.xiaoke.user.service;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.user.entity.User;

/** JWT 只证明凭证；账号当前状态由业务查询数据库后检查。 */
public final class UserState {
    private UserState() { }

    public static User requireActive(User user) {
        if (user == null || !Integer.valueOf(0).equals(user.getDeleted())) {
            throw new BusinessException(401, "登录身份已失效");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BusinessException(403, "账号已禁用");
        }
        return user;
    }
}
