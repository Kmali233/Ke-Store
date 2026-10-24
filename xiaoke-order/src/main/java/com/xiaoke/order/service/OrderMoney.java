package com.xiaoke.order.service;
import com.xiaoke.common.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class OrderMoney {
    public static final BigDecimal MAX=new BigDecimal("99999999.99");
    private OrderMoney() { }
    public static BigDecimal price(BigDecimal value) {
        if (value==null || value.signum()<0 || value.compareTo(MAX)>0 || value.scale()>2) { throw new BusinessException(500,"商品金额异常"); }
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }
    public static BigDecimal amount(BigDecimal price,int quantity,int failureCode) {
        BigDecimal value=price(price).multiply(BigDecimal.valueOf(quantity));
        return bounded(value,failureCode);
    }
    public static BigDecimal bounded(BigDecimal value,int failureCode) {
        if (value.signum()<0 || value.compareTo(MAX)>0) { throw new BusinessException(failureCode,"订单金额超过上限"); }
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }
}
