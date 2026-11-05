package com.xiaoke.admin.bootstrap;

import com.xiaoke.admin.dto.AdminLoginDTO;

import jakarta.validation.Validator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import com.xiaoke.audit.*;
import static com.xiaoke.audit.AuditChanges.Field.*;

/** 离线初始化、恢复，初始化管理员账号 */
@Component
@ConditionalOnProperty(prefix = "xiaoke.admin.provision", name = "enabled", havingValue = "true")
public class AdminProvisioning implements ApplicationRunner {
    // implements ApplicationRunner	对象创建后，
    // 由 Spring Boot 在启动阶段调用它的 run()

    private final Environment env;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final Validator validator;
    private final AuditWriter audit;
    public AdminProvisioning(Environment env, JdbcTemplate jdbc, PasswordEncoder passwords, Validator validator, AuditWriter audit) {
        this.env = env; this.jdbc = jdbc; this.passwords = passwords; this.validator = validator;
        this.audit=audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"none".equalsIgnoreCase(env.getProperty("spring.main.web-application-type"))) {
            throw new IllegalStateException("Administrator provisioning requires an offline non-web process");
        }
        var dto = new AdminLoginDTO();
        dto.setUsername(env.getProperty("xiaoke.admin.provision.username"));
        dto.setPassword(env.getProperty("xiaoke.admin.provision.password"));
        if (!validator.validate(dto).isEmpty() || !dto.getPassword().matches("(?s)(?=.*[A-Za-z])(?=.*[0-9]).*")) {
            throw new IllegalStateException("Provisioning requires username and 12-64 printable ASCII password with letters and digits");
        }
        String username = dto.getUsername().toLowerCase(Locale.ROOT);
        String hash = passwords.encode(dto.getPassword());
        String mode = env.getProperty("xiaoke.admin.provision.mode", "create");
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())))
                .executeWithoutResult(tx -> write(mode, username, hash));
        // No usernames, passwords, hashes or JWTs in output.
    }
    private void write(String mode, String username, String hash) {
        int changed;
        if ("create".equals(mode)) {
            // Explicit id=1 plus the PK prevents two simultaneous empty-table initializers creating two admins.
            changed = jdbc.update("""
                    INSERT INTO t_admin_account(id,username,password)
                    SELECT 1,?,? FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t_admin_account)
                    """, username, hash);
        } else if ("recover".equals(mode)) {
            // Recovery never silently re-enables a disabled/deleted account.
            changed = jdbc.update("UPDATE t_admin_account SET password=?,token_version=token_version+1 WHERE username=? AND deleted=0",
                    hash, username);
        } else {
            throw new IllegalStateException("Provision mode must be create or recover");
        }
        if (changed != 1) { throw new IllegalStateException("Provisioning refused: expected exactly one eligible account"); }
        if ("create".equals(mode)) {
            if (jdbc.update("INSERT INTO t_admin_account_role(admin_id,role_id) VALUES(1,1)") != 1) {
                throw new IllegalStateException("Initial system role assignment failed");
            }
            audit.record(AuditAction.ADMIN_CREATE,1,AuditChanges.start().put(STATUS,null,1).put(ROLE_IDS,java.util.List.of(),java.util.List.of(1L)));
        } else {
            long id=java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM t_admin_account WHERE username=?",Long.class,username));
            audit.record(AuditAction.ADMIN_CREDENTIAL_RECOVER,id,AuditChanges.start().put(SESSION_REVOKED,false,true));
        }
    }
}
