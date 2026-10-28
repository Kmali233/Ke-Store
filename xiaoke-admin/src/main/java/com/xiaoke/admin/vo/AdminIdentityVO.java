package com.xiaoke.admin.vo;

/** Response whitelist; never exposes credential hash or revocation version. */
public record AdminIdentityVO(long id, String username) { }
