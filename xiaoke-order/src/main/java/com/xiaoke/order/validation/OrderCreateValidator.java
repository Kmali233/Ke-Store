package com.xiaoke.order.validation;
import com.xiaoke.order.dto.OrderCreateDTO;
import jakarta.validation.*;
import java.util.HashSet;

/**
 * 执行两项检查：收货来源是否合法、商品 ID 是否重复
 */
public class OrderCreateValidator
        implements ConstraintValidator<ValidOrderCreate,OrderCreateDTO> {
    public boolean isValid(OrderCreateDTO value, ConstraintValidatorContext context) {
        if (value==null) { return true; }
        if (!value.validShippingSource()) { return false; }
        if (value.getItems()==null) { return true; }
        var ids=new HashSet<Long>();
        for (var item:value.getItems()) {
            if (item!=null && item.getProductId()!=null
                    && !ids.add(item.getProductId())) {
                return false;
            }
        }
        return true;
    }
}
