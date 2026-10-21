package com.xiaoke.common.exception;

import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;

/** 数据库故障只按类型/SQLState/vendor code 分类，绝不分析或记录原始 SQL 消息。
 *
 * 例如下单时，程序遇到提交异常，但无法确认数据库到底有没有成功保存订单，
 * 就不能简单提示用户“失败了，再点一次”。当前代码会把这类情况单独识别出来。
 * 需要把锁冲突转换成 409 的事务，则要主动通过 DatabaseFailures.execute(...)
 * 这个事务执行入口调用，才有对应的回滚确认逻辑
 * */
public final class DatabaseFailures {
    private DatabaseFailures() { }

    public static boolean connectionUnavailable(Throwable failure) {
        for (Throwable cause : causes(failure)) {
            if (cause instanceof CannotGetJdbcConnectionException) { return true; }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) { return true; }
        }
        return false;
    }

    public static boolean outcomeUnknown(Throwable failure) {
        return causes(failure).stream().anyMatch(cause -> cause instanceof TransactionSystemException
                && !(cause instanceof CannotCreateTransactionException));
    }

    private static boolean lockConflict(Throwable failure) {
        return causes(failure).stream().anyMatch(cause -> cause instanceof SQLException sql
                && (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213));
    }

    /**
     * 需要将死锁/锁等待超时转为409的事务通过此边界调用。
     * 只在 afterCompletion 确认整笔回滚后转换；不重试，不把提交结果未知伪装成冲突。
     * 事务属性由业务配置的 TransactionOperations 决定；不能用外层事务包住此调用。
     */
    public static <T> T execute(TransactionOperations transaction, Supplier<T> action) {
        int[] completion = {TransactionSynchronization.STATUS_UNKNOWN};
        try {
            return transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int state) { completion[0] = state; }
                });
                return action.get();
            });
        } catch (RuntimeException failure) {
            if (completion[0] == TransactionSynchronization.STATUS_ROLLED_BACK
                    && !outcomeUnknown(failure) && lockConflict(failure)) {
                throw new BusinessException(409, "数据状态冲突");
            }
            throw failure;
        }
    }

    public static Set<Throwable> causes(Throwable failure) {
        Set<Throwable> causes = Collections.newSetFromMap(new IdentityHashMap<>());
        while (failure != null && causes.add(failure)) {
            failure = failure.getCause();
        }
        return causes;
    }
}
