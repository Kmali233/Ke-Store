package com.xiaoke.admin.repository;

import com.xiaoke.common.auth.admin.*;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 账号查询、撤销 SQL,相当于mapper */
@Repository
@ConditionalOnProperty(prefix = "xiaoke.admin", name = "enabled", havingValue = "true")
public class AdminAccountStore implements AdminAccountLookup {
    private final JdbcTemplate jdbc;
    public AdminAccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 根据id查询账号 */
    @Override
    public AdminAccountState findById(long adminId) {
        var accounts = jdbc.query("""
                SELECT id,username,token_version,status,deleted FROM t_admin_account WHERE id=?
                """, (rs, row) -> new AdminAccountState(rs.getLong("id"), rs.getString("username"),
                rs.getLong("token_version"), rs.getInt("status"), rs.getInt("deleted")), adminId);
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    /** 乐观锁版本更新，使同时注销请求安全。撤销所有会话。 */
    public void revoke(AdminPrincipal token) {
        int changed = jdbc.update("""
                UPDATE t_admin_account SET token_version=token_version+1
                WHERE id=? AND token_version=? AND status=1 AND deleted=0
                """, token.adminId(), token.tokenVersion());
        if (changed != 1) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
    }
}
