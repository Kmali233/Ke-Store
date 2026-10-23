package com.xiaoke.user.service;

import com.xiaoke.common.auth.JwtService;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.user.dto.LoginDTO;
import com.xiaoke.user.entity.User;
import com.xiaoke.user.mapper.UserMapper;
import com.xiaoke.user.service.impl.UserServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAuthenticationTest {
    static ValidatorFactory factory;
    UserMapper mapper;
    PasswordEncoder encoder;
    JwtService jwt;
    UserService service;
    User user;

    @BeforeAll static void before() { factory = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void after() { factory.close(); }

    @BeforeEach
    void setUp() {
        mapper = mock(UserMapper.class); encoder = mock(PasswordEncoder.class); jwt = mock(JwtService.class);
        service = new UserServiceImpl(mapper, encoder, mock(UserRegistrationTxService.class), factory.getValidator(), jwt);
        user = new User(); user.setId(42L); user.setUsername("test_user"); user.setPassword("test-hash");
        user.setStatus(1); user.setDeleted(0);
    }

    @Test
    void loginReturnsOnlyTokenLifetimeAndSummaryAfterPasswordMatches() {
        when(mapper.findUndeleted("test_user")).thenReturn(user);
        when(encoder.matches(" Abc123 ", "test-hash")).thenReturn(true);
        when(jwt.issue(42L, "test_user")).thenReturn("test-token");
        var result = service.login(dto());
        assertThat(result.token()).isEqualTo("test-token");
        assertThat(result.expiresIn()).isEqualTo(7200);
        assertThat(result.userInfo().id()).isEqualTo(42);
        verify(encoder).matches(" Abc123 ", "test-hash");
    }

    @Test
    void disabledAccountWrongPasswordIs401AndCorrectPasswordIs403() {
        user.setStatus(0);
        when(mapper.findUndeleted("test_user")).thenReturn(user);
        failure(() -> service.login(dto()), 401);
        when(encoder.matches(" Abc123 ", "test-hash")).thenReturn(true);
        failure(() -> service.login(dto()), 403);
        verifyNoInteractions(jwt);
    }

    @Test
    void missingUserAndInvalidInputNeverIssueToken() {
        failure(() -> service.login(dto()), 401);
        failure(() -> service.login(null), 400);
        LoginDTO invalid = dto(); invalid.setUsername("Kevin");
        failure(() -> service.login(invalid), 400);
        verifyNoInteractions(jwt);
    }

    @Test
    void meAlwaysReadsCurrentAccountAndRejectsDeletedOrDisabledIdentity() {
        when(mapper.findByIdIncludingDeleted(42L)).thenReturn(user);
        assertThat(service.me(42L).username()).isEqualTo("test_user");
        user.setStatus(0); failure(() -> service.me(42L), 403);
        user.setDeleted(1); failure(() -> service.me(42L), 401);
        failure(() -> service.me(99L), 401);
        failure(() -> service.me(null), 401);
    }

    private static LoginDTO dto() {
        var dto = new LoginDTO(); dto.setUsername(" TEST_USER "); dto.setPassword(" Abc123 "); return dto;
    }

    private static void failure(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                value -> assertThat(value.getCode()).isEqualTo(code));
    }
}
