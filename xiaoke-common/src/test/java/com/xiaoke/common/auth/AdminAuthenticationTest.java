package com.xiaoke.common.auth;

import com.xiaoke.common.auth.admin.*;
import com.xiaoke.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminAuthenticationTest {
    @Test void liveAccountStateIsReadForEveryAuthenticationAndRevocationIsImmediate() {
        var jwt = mock(AdminJwtService.class);
        var lookup = mock(AdminAccountLookup.class);
        var principal = new AdminPrincipal(1, "ops_admin", 2);
        when(jwt.verify("synthetic")).thenReturn(principal);
        when(lookup.findById(1)).thenReturn(new AdminAccountState(1, "ops_admin", 2, 1, 0),
                new AdminAccountState(1, "ops_admin", 3, 1, 0));
        var authentication = new AdminAuthentication(jwt, lookup);
        assertThat(authentication.authenticate("synthetic")).isEqualTo(principal);
        assertThatThrownBy(() -> authentication.authenticate("synthetic")).isInstanceOf(BusinessException.class);
        verify(lookup, times(2)).findById(1);
    }
    @Test void accountStatusDeletionUsernameAndVersionAreValidatedAtOneSharedInterface() {
        var principal = new AdminPrincipal(1, "ops_admin", 2);
        var invalid = java.util.Arrays.asList(null,
                new AdminAccountState(1, "ops_admin", 2, 0, 0),
                new AdminAccountState(1, "ops_admin", 2, 1, 1),
                new AdminAccountState(1, "other_admin", 2, 1, 0),
                new AdminAccountState(1, "ops_admin", 3, 1, 0),
                new AdminAccountState(2, "ops_admin", 2, 1, 0));
        for (var state : invalid) {
            var authentication = new AdminAuthentication(mock(AdminJwtService.class), id -> state);
            assertThatThrownBy(() -> authentication.requireActive(principal)).isInstanceOf(BusinessException.class);
        }
    }
}
