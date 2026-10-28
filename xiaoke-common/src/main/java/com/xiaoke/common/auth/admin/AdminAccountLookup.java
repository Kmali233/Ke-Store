package com.xiaoke.common.auth.admin;

/** Read-only seam. Implementations live in the applications, never in common. */
public interface AdminAccountLookup {
    AdminAccountState findById(long adminId);
}
