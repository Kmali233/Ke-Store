package com.xiaoke.common.auth.admin;

public record AdminPrincipal(long adminId, String username, long tokenVersion) { }
