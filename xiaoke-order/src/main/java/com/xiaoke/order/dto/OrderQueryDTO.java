package com.xiaoke.order.dto;
import com.xiaoke.common.web.PageQuery;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class OrderQueryDTO extends PageQuery {
    @Min(0) @Max(5) private Integer status;
}
