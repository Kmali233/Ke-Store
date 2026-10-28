package com.xiaoke.common.auth.admin;

/** Minimal authentication state; no password hash or write capability crosses this interface. */
public record AdminAccountState(long id, String username, long tokenVersion, int status, int deleted) { }
