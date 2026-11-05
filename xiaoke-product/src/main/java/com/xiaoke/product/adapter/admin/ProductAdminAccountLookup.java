package com.xiaoke.product.adapter.admin;

import com.xiaoke.common.auth.admin.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only adapter for the current shared-MySQL deployment. No credential or account writes. */
@Repository
@ConditionalOnProperty(prefix = "xiaoke.admin", name = "enabled", havingValue = "true")
public class ProductAdminAccountLookup implements AdminAccountLookup, AdminPermissionLookup {
    private final JdbcTemplate jdbc;
    public ProductAdminAccountLookup(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override
    public AdminAccountState findById(long adminId) {
        var rows = jdbc.query("SELECT id,username,token_version,status,deleted FROM t_admin_account WHERE id=?",
                (rs, row) -> new AdminAccountState(rs.getLong("id"), rs.getString("username"),
                        rs.getLong("token_version"), rs.getInt("status"), rs.getInt("deleted")), adminId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Override
    public java.util.Set<String> permissions(long adminId) {
        return java.util.Set.copyOf(jdbc.queryForList("""
                SELECT DISTINCT rp.permission_code FROM t_admin_account_role ar
                JOIN t_admin_role_permission rp ON rp.role_id=ar.role_id
                JOIN t_admin_account a ON a.id=ar.admin_id
                WHERE ar.admin_id=? AND a.status=1 AND a.deleted=0
                """, String.class, adminId));
    }
}
