package com.xiaoke.order.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminOrderQueryDTO extends OrderQueryDTO {
    @Pattern(regexp = "[0-9]{14}")
    private String orderNo;
}
