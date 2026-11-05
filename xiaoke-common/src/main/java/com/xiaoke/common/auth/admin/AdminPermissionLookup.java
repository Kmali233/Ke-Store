package com.xiaoke.common.auth.admin;

import java.util.Set;

/** Fresh read on every operation. No positive authorization cache. */
public interface AdminPermissionLookup {
    Set<String> permissions(long adminId);
}
