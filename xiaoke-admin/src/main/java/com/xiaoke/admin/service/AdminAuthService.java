package com.xiaoke.admin.service;

import com.xiaoke.admin.vo.AdminIdentityVO;
import com.xiaoke.admin.vo.AdminLoginVO;
import com.xiaoke.admin.dto.AdminLoginDTO;
import com.xiaoke.admin.repository.AdminAccountStore;

import com.xiaoke.common.auth.admin.*;
import com.xiaoke.common.exception.BusinessException;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@ConditionalOnProperty(prefix = "xiaoke.admin", name = "enabled", havingValue = "true")
public class AdminAuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AdminJwtService jwt;
    private final AdminAccountStore accounts;
    private final AdminAuthentication authentication;
    private final Validator validator;
    private final String dummyHash;

    public AdminAuthService(JdbcTemplate jdbc, PasswordEncoder passwords, AdminJwtService jwt,
                            AdminAccountStore accounts, AdminAuthentication authentication, Validator validator) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.jwt = jwt;
        this.accounts = accounts;
        this.authentication = authentication;
        this.validator = validator;
        dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
    }

    public AdminLoginVO login(AdminLoginDTO dto) {
        if (dto == null || !validator.validate(dto).isEmpty()) { throw new BusinessException(400, "登录参数不合法"); }
        String username = dto.getUsername().toLowerCase(Locale.ROOT);
        var rows = jdbc.query("SELECT id,username,password,token_version,status,deleted FROM t_admin_account WHERE username=?",
                (rs, n) -> new Credentials(new AdminPrincipal(rs.getLong("id"), rs.getString("username"),
                        rs.getLong("token_version")), rs.getString("password"), rs.getInt("status"), rs.getInt("deleted")), username);
        var row = rows.isEmpty() ? null : rows.get(0);
        // Missing users still perform BCrypt; outward failures do not disclose account state.
        boolean matched = passwords.matches(dto.getPassword(), row == null ? dummyHash : row.hash);
        if (row == null || !matched || row.status != 1 || row.deleted != 0) {
            throw new BusinessException(401, "用户名或密码错误，或账号不可用");
        }
        var principal = authentication.requireActive(row.principal);
        return new AdminLoginVO(jwt.issue(principal), AdminJwtService.EXPIRY_SECONDS,
                new AdminIdentityVO(principal.adminId(), principal.username()));
    }

    public void logout() { accounts.revoke(AdminContext.requirePrincipal()); }

    // Internal only, never serialized, logged or returned to controllers.
    private static final class Credentials {
        final AdminPrincipal principal;
        final String hash;
        final int status;
        final int deleted;
        Credentials(AdminPrincipal principal, String hash, int status, int deleted) {
            this.principal = principal; this.hash = hash; this.status = status; this.deleted = deleted;
        }
    }
}
