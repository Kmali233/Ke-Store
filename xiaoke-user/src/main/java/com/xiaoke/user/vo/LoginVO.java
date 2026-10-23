package com.xiaoke.user.vo;

public record LoginVO(String token, long expiresIn, UserSummaryVO userInfo) { }
