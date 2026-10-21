package com.xiaoke.product;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.exception.DatabaseFailures;
import com.xiaoke.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 使用已有 MySQL 驱动；只允许专门的临时核验库，不触碰 xiaoke_mall。 */
@EnabledIfEnvironmentVariable(named = "XIAOKE_RUN_DB_TESTS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommonDatabaseIntegrationTest {
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;

    @BeforeAll
    void setUp() throws Exception {
        source = new DriverManagerDataSource(System.getenv("XIAOKE_TEST_DB_URL"),
                System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        try (var connection = source.getConnection()) {
            if (!connection.getCatalog().matches("xiaoke_common_verify_[a-z0-9_]+")) {
                throw new IllegalStateException("Refusing to modify a non-verification database");
            }
        }
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE tx_probe (id INT PRIMARY KEY, value INT NOT NULL) ENGINE=InnoDB");
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM tx_probe");
        jdbc.update("INSERT INTO tx_probe VALUES (1,0),(2,0)");
    }

    @AfterAll
    void tearDown() {
        if (jdbc != null) { jdbc.execute("DROP TABLE IF EXISTS tx_probe"); }
    }

    @Test
    void lockTimeoutRollsBackEarlierWritesBeforeReturning409() throws Exception {
        try (Connection blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.executeUpdate("UPDATE tx_probe SET value=1 WHERE id=1");
                var ex = assertThrows(BusinessException.class, () -> DatabaseFailures.execute(tx, () -> {
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                    jdbc.update("UPDATE tx_probe SET value=5 WHERE id=2");
                    jdbc.update("UPDATE tx_probe SET value=7 WHERE id=1");
                    return null;
                }));
                assertThat(ex.getCode()).isEqualTo(409);
                assertThat(jdbc.queryForObject("SELECT value FROM tx_probe WHERE id=2", Integer.class)).isZero();
            } finally { blocker.rollback(); }
        }
    }

    @Test
    void realDeadlockHasOneVictimAndNoPartialWrites() throws Exception {
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> updateOpposite(1, 2, barrier));
            var second = executor.submit(() -> updateOpposite(2, 1, barrier));
            int successes = first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS);
            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT value FROM tx_probe ORDER BY id", Integer.class)).containsExactly(1, 1);
        } finally { executor.shutdownNow(); }
    }

    private int updateOpposite(int first, int second, CyclicBarrier barrier) {
        try {
            return DatabaseFailures.execute(tx, () -> {
                jdbc.update("UPDATE tx_probe SET value=value+1 WHERE id=?", first);
                try { barrier.await(5, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new IllegalStateException("Barrier failed", ex); }
                jdbc.update("UPDATE tx_probe SET value=value+1 WHERE id=?", second);
                return 1;
            });
        } catch (BusinessException ex) {
            assertThat(ex.getCode()).isEqualTo(409);
            return 0;
        }
    }

    @Test
    void closedConnectionEndpointIs503() {
        var offline = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:1/unavailable?connectTimeout=1000&socketTimeout=1000", "unused", "unused"));
        var failure = assertThrows(RuntimeException.class, () -> offline.queryForObject("SELECT 1", Integer.class));
        var response = new GlobalExceptionHandler().handleException(failure);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().getCode()).isEqualTo(503);
    }
}
