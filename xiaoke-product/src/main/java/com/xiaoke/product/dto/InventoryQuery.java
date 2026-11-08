package com.xiaoke.product.dto;
import com.xiaoke.common.web.PageQuery;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class InventoryQuery extends PageQuery {
    @Pattern(regexp="OPENING|INBOUND|ADJUSTMENT|ORDER_OCCUPY|CANCEL_RESTORE|REFUND_RESTORE") private String type;
    @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}") private String businessNo;
    @Override public void validatePage() {
        super.validatePage();
        if(getPageNum()>10000) throw new com.xiaoke.common.exception.BusinessException(400,"分页过深");
    }
}
