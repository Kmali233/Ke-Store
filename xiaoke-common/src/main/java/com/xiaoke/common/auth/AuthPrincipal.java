package com.xiaoke.common.auth;



/**
 * 已验签的最小身份。不含密码、角色、账号状态。
 */
public record AuthPrincipal(Long userId, String username) {  //不可变、不可继承
}
