package com.xiaoke.order.service;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.entity.*;
import java.util.*;
/** 取消读取全部明细，不能因软删/重复/缺失而仅恢复部分库存。 */
public final class OrderItemRules {
    private OrderItemRules() { }
    public static List<OrderItem> checked(Orders order,List<OrderItem> items) {
        if (items==null || items.isEmpty() || items.size()>20) { throw broken(); }
        var ids=new HashSet<String>();
        for (var item:items) {
            if (item==null || !Objects.equals(item.getOrderId(),order.getId()) || !Objects.equals(item.getOrderNo(),order.getOrderNo())
                    || !Integer.valueOf(0).equals(item.getDeleted()) || item.getProductId()==null || item.getProductId()<=0
                    || !ids.add(item.getSkuId()==null?"p"+item.getProductId():"s"+item.getSkuId())
                    || (item.getSkuId()!=null && (item.getSkuId()<=0 || item.getSkuName()==null || item.getSkuSpec()==null))
                    || item.getQuantity()==null || item.getQuantity()<1 || item.getQuantity()>999
                    || item.getProductName()==null || item.getProductName().isBlank()) { throw broken(); }
            OrderMoney.amount(item.getPrice(),item.getQuantity(),500);
        }
        return items.stream().sorted(Comparator.comparing(OrderItem::getProductId)).toList();
    }
    private static BusinessException broken() { return new BusinessException(500,"订单明细异常"); }
}
