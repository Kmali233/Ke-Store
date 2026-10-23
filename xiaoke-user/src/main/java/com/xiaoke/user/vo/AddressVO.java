package com.xiaoke.user.vo;

import java.time.LocalDateTime;

public record AddressVO(Long id, String receiverName, String receiverPhone,
                        String province, String city, String district, String detailAddress,
                        Boolean isDefault, LocalDateTime createTime, LocalDateTime updateTime) { }
