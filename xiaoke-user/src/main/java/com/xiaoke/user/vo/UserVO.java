package com.xiaoke.user.vo;

import java.time.LocalDateTime;

public record UserVO(Long id, String username, String nickname, String phone,
                     Integer status, LocalDateTime createTime) { }
