package com.xiaoke.order.service;
import com.xiaoke.common.exception.DatabaseFailures;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.util.function.Supplier;

/** 事务完成后才返回或抛出；Facade的按日锁因此覆盖提交及回滚。 */
@Component
public class OrderTransactions {
    private final TransactionTemplate create,write,read;
    public OrderTransactions(PlatformTransactionManager manager) {
        create=new TransactionTemplate(manager);
        create.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        create.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write=new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read=new TransactionTemplate(manager);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
    }
    public <T> T create(Supplier<T> action) { return execute(create,action); }
    public <T> T write(Supplier<T> action) { return execute(write,action); }
    public <T> T read(Supplier<T> action) { return execute(read,action); }
    public void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Order entry must not join an outer transaction"); }
    }
    private <T> T execute(TransactionTemplate template,Supplier<T> action) {
        outsideTransaction();
        return DatabaseFailures.execute(template,action);
    }
}
