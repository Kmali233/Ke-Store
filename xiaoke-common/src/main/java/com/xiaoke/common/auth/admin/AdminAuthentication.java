package com.xiaoke.common.auth.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;

@Service
@ConditionalOnProperty(prefix = "xiaoke.admin", name = "enabled", havingValue = "true")
public class AdminAuthentication {
    private final AdminJwtService jwt;
    private final AdminAccountLookup accounts;
    public AdminAuthentication(AdminJwtService jwt, AdminAccountLookup accounts) {
        this.jwt = jwt;
        this.accounts = accounts;
    }
    public AdminPrincipal authenticate(String token) { return requireActive(jwt.verify(token)); }

    /**
     * 验证用户状态
     */
    public AdminPrincipal requireActive(AdminPrincipal token) {
        var state = accounts.findById(token.adminId());
        if (state == null || state.status() != 1 || state.deleted() != 0
                || state.id() != token.adminId() || state.tokenVersion() != token.tokenVersion()
                || !token.username().equals(state.username())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return token;
    }
}
