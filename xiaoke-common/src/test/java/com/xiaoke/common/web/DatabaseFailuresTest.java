package com.xiaoke.common.web;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.exception.DatabaseFailures;
import com.xiaoke.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseFailuresTest {
    @Test
    void mapsOnlyConfirmedRollbackOfKnownMysqlLockErrors() {
        var manager = new TestTransactions();
        var tx = new TransactionTemplate(manager);
        for (int code : new int[]{1205, 1213}) {
            BusinessException ex = assertThrows(BusinessException.class, () -> DatabaseFailures.execute(tx, () -> {
                throw new UncategorizedSQLException("test", "private SQL", new SQLException("private payload", "40001", code));
            }));
            assertThat(ex.getCode()).isEqualTo(409);
        }
        assertThat(manager.rollbacks).isEqualTo(2);
        var unknown = new UncategorizedSQLException("test", "private SQL", new SQLException("private", "23000", 1062));
        assertThat(assertThrows(RuntimeException.class,
                () -> DatabaseFailures.execute(tx, () -> { throw unknown; }))).isSameAs(unknown);
        // 无回滚证明的原始锁错误不能由全局处理器猜测为409。
        var raw = new UncategorizedSQLException("test", "SQL", new SQLException("private", "HY000", 1205));
        assertThat(new GlobalExceptionHandler().handleException(raw).getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void classifiesConnectionsAndPreservesUnknownCommitOutcome() {
        var handler = new GlobalExceptionHandler();
        var offline = new CannotGetJdbcConnectionException("private connection string", new SQLException("secret", "08001"));
        assertThat(handler.handleException(offline).getStatusCode().value()).isEqualTo(503);
        var manager = new TestTransactions(); manager.failCommit = true;
        var failure = assertThrows(TransactionSystemException.class,
                () -> DatabaseFailures.execute(new TransactionTemplate(manager), () -> 1));
        var result = handler.handleException(failure);
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().getMessage()).contains("核对");
        assertThat(result.getBody().getMessage()).doesNotContain("private");
        assertThat(result.getBody().getData()).isNull();
    }

    static class TestTransactions extends AbstractPlatformTransactionManager {
        int rollbacks;
        boolean failCommit;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object tx, TransactionDefinition definition) { }
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) { throw new TransactionSystemException("private commit failure"); }
        }
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
