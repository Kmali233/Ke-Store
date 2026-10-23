package com.xiaoke.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddressUpdateDTO extends AddressInput {
    @NotNull @Positive
    private Long id;
    @NotNull
    private Boolean isDefault;
}
