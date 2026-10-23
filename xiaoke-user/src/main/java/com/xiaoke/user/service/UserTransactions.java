package com.xiaoke.user.service;

import com.xiaoke.common.exception.DatabaseFailures;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** user 的写事务入口：统一隔离级别，确认回滚后才把锁冲突转换为 409。 */
@Component
public class UserTransactions {
    private final TransactionTemplate template;

    public UserTransactions(PlatformTransactionManager manager) {
        template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public <T> T execute(Supplier<T> action) {
        /**
         * action：UserRegistrationTxService 那段“插入用户、检查行数”的操作
         * template：Spring 的 TransactionTemplate，负责事务的开启和提交/回滚
         * */

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("User write entry must not join an outer transaction");
        }
        return DatabaseFailures.execute(template, action);
    }
}
