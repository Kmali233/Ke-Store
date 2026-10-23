package com.xiaoke.user.service;

import com.xiaoke.user.entity.User;
import com.xiaoke.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.xiaoke.common.exception.BusinessException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证真实 Spring 事务管理；Mapper 和事务资源为测试替身，不连接数据库。 */
class UserRegistrationTxServiceTest {
    private AnnotationConfigApplicationContext context;
    private UserRegistrationTxService service;
    private UserMapper mapper;
    private RecordingTransactions transactions;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        service = context.getBean(UserRegistrationTxService.class);
        mapper = context.getBean(UserMapper.class);
        transactions = context.getBean(RecordingTransactions.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void commitsSuccessfulInsertInReadCommittedTransaction() {
        User user = new User();
        when(mapper.insert(user)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            transactions.events.add("insert");
            return 1;
        });

        assertThat(service.insertUser(user)).isEqualTo(1);
        assertThat(transactions.events).containsExactly("begin", "insert", "commit");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(mapper, times(1)).insert(user);
    }

    @Test
    void rollsBackDuplicateBeforeCallerReceivesOriginalException() {
        User user = new User();
        var failure = new DuplicateKeyException("test duplicate");
        when(mapper.insert(user)).thenAnswer(invocation -> {
            transactions.events.add("insert");
            throw failure;
        });

        assertThatThrownBy(() -> service.insertUser(user)).isSameAs(failure);
        assertThat(transactions.events).containsExactly("begin", "insert", "rollback");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(mapper, times(1)).insert(user);
    }

    @Test
    void propagatesCommitFailureWithoutRetryOrSuccessResult() {
        User user = new User();
        var failure = new TransactionSystemException("test unknown commit result");
        transactions.commitFailure = failure;
        when(mapper.insert(user)).thenAnswer(invocation -> {
            transactions.events.add("insert");
            return 1;
        });

        assertThatThrownBy(() -> service.insertUser(user)).isSameAs(failure);
        assertThat(transactions.events).containsExactly("begin", "insert", "commit");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(mapper, times(1)).insert(user);
    }

    @ParameterizedTest
    @ValueSource(ints = {1205, 1213})
    void mapsLockConflictOnlyAfterConfirmedRollback(int vendor) {
        User user = new User();
        when(mapper.insert(user)).thenAnswer(invocation -> {
            transactions.events.add("insert");
            throw new CannotAcquireLockException("test lock", new SQLException("test", "40001", vendor));
        });
        assertThatThrownBy(() -> service.insertUser(user)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(409));
        assertThat(transactions.events).containsExactly("begin", "insert", "rollback");
        verify(mapper, times(1)).insert(user);
    }

    @Test
    void translatedCommitFailureStillHasUnknownOutcome() {
        User user = new User();
        var failure = new CannotAcquireLockException("test commit", new SQLException("test", "40001", 1213));
        transactions.commitFailure = failure;
        when(mapper.insert(user)).thenReturn(1);
        assertThatThrownBy(() -> service.insertUser(user)).isInstanceOf(TransactionSystemException.class)
                .hasCause(failure);
        // 提交异常后框架可能尝试 rollback，但不能据此断言提交一定没发生。
        assertThat(transactions.events).containsExactly("begin", "commit", "rollback");
    }

    @Configuration
    @EnableTransactionManagement
    @Import({UserRegistrationTxService.class, UserTransactions.class})
    static class TestConfig {
        @Bean
        UserMapper userMapper() {
            return mock(UserMapper.class);
        }

        @Bean
        RecordingTransactions transactionManager() {
            return new RecordingTransactions();
        }
    }

    static class RecordingTransactions extends AbstractPlatformTransactionManager {
        private final List<String> events = new ArrayList<>();
        private RuntimeException commitFailure;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
            if (commitFailure != null) {
                throw commitFailure;
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }
}
