package com.xiaoke.user.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddressCreateDTO extends AddressInput {
    /** 省略表示按首条规则决定；显式 JSON null 不合法。 */
    @JsonSetter(nulls = Nulls.FAIL)
    private Boolean isDefault;
}
