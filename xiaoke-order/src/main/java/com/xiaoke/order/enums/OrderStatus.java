package com.xiaoke.order.enums;
import com.xiaoke.common.exception.BusinessException;
public enum OrderStatus {
    PENDING_PAYMENT(0,"待付款"), PAID(1,"已付款"), PREPARING(2,"备货中"),
    SHIPPED(3,"已发货"), RECEIVED(4,"已签收"), CANCELED(5,"已取消");
    private final int code;
    private final String description;

    OrderStatus(int code,String description) {
        this.code=code; this.description=description;
    }

    public int code() { return code; }
    public String description() { return description; }

    public static OrderStatus fromCode(int code) {
        for (var status:values()) { if (status.code==code) { return status; } }
        throw new BusinessException(500,"订单状态异常");
    }
}
