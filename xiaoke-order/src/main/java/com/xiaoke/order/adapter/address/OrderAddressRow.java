package com.xiaoke.order.adapter.address;
import lombok.Data;


/**
 * 用于装数据库查询结果,职责就是承载数据
 */
@Data
public class OrderAddressRow {
    private String receiverName,receiverPhone,province,city,
            district,detailAddress;
}
