package com.xiaoke.user.service;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.auth.JwtService;
import com.xiaoke.user.dto.RegisterDTO;
import com.xiaoke.user.entity.User;
import com.xiaoke.user.mapper.UserMapper;
import com.xiaoke.user.service.impl.UserServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证业务编排，数据库与事务组件使用测试替身，不执行真实 SQL。 */
class UserServiceImplTest {
    private static ValidatorFactory factory;
    private UserMapper mapper;
    private PasswordEncoder encoder;
    private UserRegistrationTxService writer;
    private UserService service;

    @BeforeAll
    static void startValidation() {
        factory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidation() {
        factory.close();
    }

    @BeforeEach
    void setUp() {
        mapper = mock(UserMapper.class);
        encoder = mock(PasswordEncoder.class);
        writer = mock(UserRegistrationTxService.class);
        service = new UserServiceImpl(mapper, encoder, writer, factory.getValidator(), mock(JwtService.class));
    }

    @Test
    void preparesNormalizedUserAndSavesOnlyEncodedPassword() {
        RegisterDTO dto = validRequest();
        when(encoder.encode(" Abc123 ")).thenReturn("test-encoded-value");
        when(writer.insertUser(any(User.class))).thenReturn(1);

        service.register(dto);

        var user = ArgumentCaptor.forClass(User.class);
        var order = inOrder(mapper, encoder, writer);
        order.verify(mapper).findIncludingDeleted("test_user");
        order.verify(encoder).encode(" Abc123 ");
        order.verify(writer).insertUser(user.capture());
        assertThat(user.getValue().getUsername()).isEqualTo("test_user");
        assertThat(user.getValue().getPassword()).isEqualTo("test-encoded-value");
        assertThat(user.getValue().getNickname()).isEqualTo("测试用户");
        assertThat(user.getValue().getPhone()).isEqualTo("13800000001");
        assertThat(user.getValue().getStatus()).isEqualTo(1);
        assertThat(user.getValue().getDeleted()).isZero();
        assertThat(user.getValue().getId()).isNull();
        assertThat(user.getValue().getCreateTime()).isNull();
        assertThat(user.getValue().getUpdateTime()).isNull();
        assertThat(dto.getUsername()).isEqualTo("Test_User");
        verifyNoMoreInteractions(mapper, encoder, writer);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"abc", "Kevin", "测试用户", "user-name"})
    void rejectsInvalidUsernameBeforeNormalizationOrDatabaseAccess(String username) {
        RegisterDTO dto = validRequest();
        dto.setUsername(username);
        assertBusinessFailure(() -> service.register(dto), 400, "注册参数不合法");
        verifyNoInteractions(mapper, encoder, writer);
    }

    @Test
    void rejectsNullRequestAndShortPassword() {
        assertBusinessFailure(() -> service.register(null), 400, "注册参数不合法");
        RegisterDTO dto = validRequest();
        dto.setPassword("12345");
        assertBusinessFailure(() -> service.register(dto), 400, "注册参数不合法");
        verifyNoInteractions(mapper, encoder, writer);
    }

    @ParameterizedTest
    @CsvSource({"1,0", "0,0", "1,1"})
    void existingNormalDisabledOrDeletedUserStillOccupiesName(int status, int deleted) {
        User existing = new User();
        existing.setStatus(status);
        existing.setDeleted(deleted);
        when(mapper.findIncludingDeleted("test_user")).thenReturn(existing);
        assertBusinessFailure(() -> service.register(validRequest()), 400, "用户名已存在");
        verifyNoInteractions(encoder, writer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"uk_username", "t_user.uk_username", "xiaoke_mall.t_user.uk_username"})
    void mapsRecognizedUsernameUniqueConflictAfterWriteFailure(String key) {
        var failure = duplicate("Duplicate entry 'test_user' for key '" + key + "'", 1062, "23000");
        when(writer.insertUser(any(User.class))).thenThrow(failure);
        assertBusinessFailure(() -> service.register(validRequest()), 400, "用户名已存在");
        verify(writer, times(1)).insertUser(any(User.class));
    }

    @ParameterizedTest
    @CsvSource({"1062,23000,PRIMARY", "1062,23000,other_index", "1213,40001,uk_username",
            "1062,42000,uk_username"})
    void preservesOtherKeysAndUnrecognizedDatabaseFailures(int vendor, String state, String key) {
        var failure = duplicate("Duplicate entry 'uk_username' for key '" + key + "'", vendor, state);
        when(writer.insertUser(any(User.class))).thenThrow(failure);
        assertThatThrownBy(() -> service.register(validRequest())).isSameAs(failure);
    }

    @Test
    void doesNotGuessConstraintWithoutSqlEvidence() {
        var failure = new DuplicateKeyException("uk_username");
        when(writer.insertUser(any(User.class))).thenThrow(failure);
        assertThatThrownBy(() -> service.register(validRequest())).isSameAs(failure);
    }

    @Test
    void mapsDuplicateAfterRealTransactionProxyCompletesRollback() {
        // 复用上一小步的事务测试配置，接入真实的事务代理验证边界。
        try (var context = new AnnotationConfigApplicationContext(UserRegistrationTxServiceTest.TestConfig.class)) {
            UserMapper realBoundaryMapper = context.getBean(UserMapper.class);
            var completion = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
            var failure = duplicate("Duplicate entry 'test_user' for key 't_user.uk_username'", 1062, "23000");
            when(realBoundaryMapper.insert(any(User.class))).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        completion.set(status);
                    }
                });
                throw failure;
            });
            UserService registration = new UserServiceImpl(realBoundaryMapper, encoder,
                    context.getBean(UserRegistrationTxService.class), factory.getValidator(), mock(JwtService.class));

            assertBusinessFailure(() -> registration.register(validRequest()), 400, "用户名已存在");
            assertThat(completion.get()).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            verify(realBoundaryMapper, times(1)).insert(any(User.class));
        }
    }

    @Test
    void propagatesConnectionAndUnknownCommitFailures() {
        var unavailable = new CannotGetJdbcConnectionException("test unavailable");
        when(mapper.findIncludingDeleted("test_user")).thenThrow(unavailable);
        assertThatThrownBy(() -> service.register(validRequest())).isSameAs(unavailable);
        verifyNoInteractions(encoder, writer);

        reset(mapper);
        var unknown = new TransactionSystemException("test unknown commit",
                new SQLException("Duplicate entry 'test_user' for key 'uk_username'", "23000", 1062));
        when(writer.insertUser(any(User.class))).thenThrow(unknown);
        assertThatThrownBy(() -> service.register(validRequest())).isSameAs(unknown);
        verify(writer, times(1)).insertUser(any(User.class));
    }

    @Test
    void refusesSuccessWhenWriterReportsNoInsertedRow() {
        when(writer.insertUser(any(User.class))).thenReturn(0);
        assertBusinessFailure(() -> service.register(validRequest()), 500, "注册保存失败");
    }

    @Test
    void usernameNormalizationDoesNotDependOnSystemLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            RegisterDTO dto = validRequest();
            dto.setUsername("TEST_I");
            when(writer.insertUser(any(User.class))).thenReturn(1);
            service.register(dto);
            verify(mapper).findIncludingDeleted("test_i");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void rejectsOuterTransactionToPreserveWriteBoundary() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.register(validRequest()))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(mapper, encoder, writer);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private static RegisterDTO validRequest() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(" Test_User ");
        dto.setPassword(" Abc123 ");
        dto.setNickname(" 测试用户 ");
        dto.setPhone(" 13800000001 ");
        return dto;
    }

    private static DuplicateKeyException duplicate(String message, int vendor, String state) {
        return new DuplicateKeyException("test duplicate", new SQLException(message, state, vendor));
    }

    private static void assertBusinessFailure(Runnable action, int code, String message) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, failure -> {
            assertThat(failure.getCode()).isEqualTo(code);
            assertThat(failure.getMessage()).isEqualTo(message);
        });
    }
}
