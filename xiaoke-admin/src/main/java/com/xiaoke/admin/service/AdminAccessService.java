package com.xiaoke.admin.service;

import com.xiaoke.admin.dto.AdminLoginDTO;
import com.xiaoke.admin.repository.AdminAccountStore;
import com.xiaoke.common.auth.admin.*;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import com.xiaoke.audit.*;
import static com.xiaoke.audit.AuditChanges.Field.*;

/** RBAC writes live only in admin. The system role row serializes all security mutations. */
@Service
public class AdminAccessService {
    private final JdbcTemplate jdbc;
    private final AdminAuthentication authentication;
    private final AdminAccountStore accounts;
    private final PasswordEncoder passwords;
    private final AuditWriter audit;
    public AdminAccessService(JdbcTemplate jdbc, AdminAuthentication authentication,
                              AdminAccountStore accounts, PasswordEncoder passwords, AuditWriter audit) {
        this.jdbc=jdbc; this.authentication=authentication; this.accounts=accounts; this.passwords=passwords; this.audit=audit;
    }

    public record Role(long id, String code, String name, List<String> permissions) { }
    public record Account(long id, String username, int status, List<Role> roles) { }
    public record Access(long id, String username, List<Role> roles, Set<String> permissions) { }

    public Access me() {
        var actor=AdminContext.requirePrincipal();
        return new Access(actor.adminId(), actor.username(), rolesFor(actor.adminId()), accounts.permissions(actor.adminId()));
    }
    public List<Role> roles() {
        return jdbc.query("SELECT id,code,name FROM t_admin_role ORDER BY id", (rs,n) ->
                new Role(rs.getLong(1),rs.getString(2),rs.getString(3),
                    jdbc.queryForList("SELECT permission_code FROM t_admin_role_permission WHERE role_id=? ORDER BY permission_code",
                            String.class,rs.getLong(1))));
    }
    private List<Role> rolesFor(long id) {
        var ids=jdbc.queryForList("SELECT role_id FROM t_admin_account_role WHERE admin_id=?",Long.class,id);
        return roles().stream().filter(r -> ids.contains(r.id())).toList();
    }
    /** Bounded by the explicit 200-account cap for this minimal administration module. */
    public List<Account> list() {
        var directory=roles();
        Map<Long,Set<Long>> assignments=new HashMap<>();
        jdbc.query("SELECT ar.admin_id,ar.role_id FROM t_admin_account_role ar JOIN t_admin_account a "
                + "ON a.id=ar.admin_id WHERE a.deleted=0", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                assignments.computeIfAbsent(rs.getLong(1), ignored -> new HashSet<>()).add(rs.getLong(2)));
        return jdbc.query("SELECT id,username,status FROM t_admin_account WHERE deleted=0 ORDER BY id LIMIT 200",
                (rs,n) -> {
                    long id=rs.getLong(1);
                    return new Account(id,rs.getString(2),rs.getInt(3),
                        directory.stream().filter(role -> assignments.getOrDefault(id,Set.of()).contains(role.id())).toList());
                });
    }
    private void lockAndAuthorize() {
        var lock=jdbc.queryForList("SELECT id FROM t_admin_role WHERE id=1 FOR UPDATE",Long.class);
        if (lock.size()!=1) { throw new IllegalStateException("RBAC migration required"); }
        var actor=authentication.requireActive(AdminContext.requirePrincipal());
        if (!accounts.permissions(actor.adminId()).contains(AdminPermissions.MANAGE)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
    private void target(long id) {
        if (id==AdminContext.requirePrincipal().adminId()) {
            throw new BusinessException(409,"不能修改自己的角色或启用状态");
        }
        if (jdbc.queryForList("SELECT id FROM t_admin_account WHERE id=? AND deleted=0 FOR UPDATE",Long.class,id).isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Account create(AdminLoginDTO dto) {
        lockAndAuthorize();
        if (!dto.getPassword().matches("(?s)(?=.*[A-Za-z])(?=.*[0-9]).*")) {
            throw new BusinessException(400,"口令必须包含字母和数字");
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_account WHERE deleted=0",Integer.class)>=200) {
            throw new BusinessException(409,"后台账号已达到200个上限");
        }
        String name=dto.getUsername().toLowerCase(Locale.ROOT);
        try {
            jdbc.update("INSERT INTO t_admin_account(username,password) VALUES(?,?)",name,passwords.encode(dto.getPassword()));
        } catch (DuplicateKeyException ex) { throw new BusinessException(409,"后台用户名已存在"); }
        long id=Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM t_admin_account WHERE username=?",Long.class,name));
        audit.record(AuditAction.ADMIN_CREATE,id,AuditChanges.start().put(STATUS,null,1));
        return new Account(id,name,1,List.of());
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void assign(long id, List<Long> roles) {
        lockAndAuthorize();
        target(id);
        if (new HashSet<>(roles).size()!=roles.size() || !this.roles().stream().map(Role::id).toList().containsAll(roles)) {
            throw new BusinessException(400,"角色不存在或重复");
        }
        var before=jdbc.queryForList("SELECT role_id FROM t_admin_account_role WHERE admin_id=? ORDER BY role_id",Long.class,id);
        jdbc.update("DELETE FROM t_admin_account_role WHERE admin_id=?",id);
        for (long role: roles) { jdbc.update("INSERT INTO t_admin_account_role(admin_id,role_id) VALUES(?,?)",id,role); }
        audit.record(AuditAction.ADMIN_ROLES_CHANGE,id,AuditChanges.start().put(ROLE_IDS,before,roles));
        // Tokens do not contain role claims: next request sees the new relationships, even on another JVM.
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void status(long id, int status) {
        lockAndAuthorize();
        target(id);
        int before=Objects.requireNonNull(jdbc.queryForObject("SELECT status FROM t_admin_account WHERE id=?",Integer.class,id));
        // Also bump on enable: old sessions never return to life after a disable/enable cycle.
        jdbc.update("UPDATE t_admin_account SET status=?,token_version=token_version+1 WHERE id=?",status,id);
        audit.record(AuditAction.ADMIN_STATUS_CHANGE,id,AuditChanges.start().put(STATUS,before,status).put(SESSION_REVOKED,false,true));
    }
}
