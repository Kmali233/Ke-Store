package com.xiaoke.product.service;

import com.xiaoke.common.exception.DatabaseFailures;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.util.function.Supplier;

/** 写入使用 RC，组合读取使用 RR 快照；锁冲突仅在确认整笔回滚后转换。 */
@Component
public class CatalogTransactions {
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    public CatalogTransactions(PlatformTransactionManager manager) {
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read = new TransactionTemplate(manager);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
    }
    public <T> T read(Supplier<T> action) { return execute(read, action); }
    public <T> T write(Supplier<T> action) { return execute(write, action); }
    private <T> T execute(TransactionTemplate template, Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Catalog entry must not join an outer transaction");
        }
        return DatabaseFailures.execute(template, action);
    }
}
