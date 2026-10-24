package com.xiaoke.product;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.product.entity.Category;
import com.xiaoke.product.mapper.CategoryMapper;
import com.xiaoke.product.service.CategoryChainPolicy;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CategoryChainPolicyTest {
    private final CategoryMapper mapper = mock(CategoryMapper.class);
    private final CategoryChainPolicy policy = new CategoryChainPolicy(mapper);

    @Test
    void missingDisabledDeletedAndCyclicAncestorsAreInvisible() {
        var rows = List.of(row(1, 0, 1, 0), row(2, 1, 1, 0), row(3, 1, 0, 0),
                row(4, 3, 1, 0), row(5, 99, 1, 0), row(6, 7, 1, 0), row(7, 6, 1, 0),
                row(8, 0, 1, 1), row(9, 8, 1, 0));
        assertThat(policy.visibleIds(rows)).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void longChainsDoNotHaveAnArtificialDepthLimit() {
        var rows = new ArrayList<Category>();
        for (int i = 1; i <= 600; i++) { rows.add(row(i, i - 1, 1, 0)); }
        assertThat(policy.visibleIds(rows)).hasSize(600);
    }

    @Test
    void mergedChainsAreLockedOnceInAscendingIdOrder() {
        for (var c : List.of(row(9, 2, 1, 0), row(2, 5, 1, 0), row(5, 0, 1, 0))) {
            when(mapper.findIncludingDeleted(c.getId())).thenReturn(c);
            when(mapper.lockById(c.getId())).thenReturn(c);
        }
        var locked = policy.lockChains(9L, 2L);
        var ordered = inOrder(mapper);
        ordered.verify(mapper).lockById(2L);
        ordered.verify(mapper).lockById(5L);
        ordered.verify(mapper).lockById(9L);
        verify(mapper, times(1)).lockById(2L);
        policy.requireUsable(9L, locked, null);
    }

    @Test
    void changedParentAbortsInsteadOfTakingAdditionalLocks() {
        when(mapper.findIncludingDeleted(1L)).thenReturn(row(1, 0, 1, 0));
        when(mapper.lockById(1L)).thenReturn(row(1, 2, 1, 0));
        assertThatThrownBy(() -> policy.lockChains(1L)).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
        verify(mapper, never()).lockById(2L);
    }

    @Test
    void unusableNewReferencesAndCyclesAreRejected() {
        assertCode(404, () -> policy.requireUsable(1L, new HashMap<>(), null));
        assertCode(409, () -> policy.requireUsable(1L, Map.of(1L, row(1, 0, 0, 0)), null));
        assertCode(409, () -> policy.requireUsable(1L, Map.of(1L, row(1, 2, 1, 0), 2L, row(2, 1, 1, 0)), null));
        assertCode(409, () -> policy.requireUsable(1L, Map.of(1L, row(1, 0, 1, 0)), 1L));
    }

    private void assertCode(int code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    private Category row(long id, long parent, int status, int deleted) {
        Category c = new Category();
        c.setId(id); c.setParentId(parent); c.setStatus(status); c.setDeleted(deleted);
        return c;
    }
}
