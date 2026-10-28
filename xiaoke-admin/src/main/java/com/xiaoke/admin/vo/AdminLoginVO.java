package com.xiaoke.admin.vo;

public record AdminLoginVO(String token, long expiresIn, AdminIdentityVO admin) { }
