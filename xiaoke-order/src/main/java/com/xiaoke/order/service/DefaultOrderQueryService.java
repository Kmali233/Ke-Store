package com.xiaoke.order.service;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.dto.OrderQueryDTO;
import com.xiaoke.order.entity.Orders;
import com.xiaoke.order.enums.OrderStatus;
import com.xiaoke.order.mapper.*;
import com.xiaoke.order.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
@Service @RequiredArgsConstructor
public class DefaultOrderQueryService implements OrderQueryService {
    private final OrderTransactions transactions;
    private final OrderUsers users;
    private final OrderInput input;
    private final OrdersMapper orders;
    private final OrderItemMapper items;
    public IPage<OrderSummaryVO> page(Long uid,OrderQueryDTO query) {
        input.check(query); query.validatePage();
        return transactions.read(()->{
            users.check(uid);
            long total=orders.countOwned(uid,query.getStatus()),offset=Math.multiplyExact(query.getPageNum()-1,query.getPageSize());
            var result=new Page<OrderSummaryVO>(query.getPageNum(),query.getPageSize(),total);
            result.setRecords(offset>=total ? List.of() : orders.pageOwned(uid,query.getStatus(),query.getPageSize(),offset).stream()
                .map(row->new OrderSummaryVO(row.getOrderNo(),row.getStatus(),status(row).description(),OrderMoney.price(row.getTotalAmount()),row.getCreateTime())).toList());
            return result;
        });
    }
    public OrderDetailVO detail(Long uid,String no) {
        input.orderNo(no);
        return transactions.read(()->{
            var order=owned(uid,no);
            var values=OrderItemRules.checked(order,items.selectActiveByOrderId(order.getId())).stream().map(item->
                new OrderItemVO(item.getProductId(),item.getProductName(),item.getProductImage(),item.getPrice(),item.getQuantity(),
                    OrderMoney.amount(item.getPrice(),item.getQuantity(),500),item.getSkuId(),item.getSkuName(),item.getSkuSpec())).toList();
            return new OrderDetailVO(no,order.getStatus(),status(order).description(),OrderMoney.price(order.getTotalAmount()),
                order.getReceiverName(),order.getReceiverPhone(),order.getReceiverAddress(),order.getRemark(),order.getLogisticsCompany(),
                order.getTrackingNo(),order.getCreateTime(),order.getPayTime(),order.getShipTime(),order.getFinishTime(),values);
        });
    }
    public OrderStatusVO status(Long uid,String no) {
        input.orderNo(no);
        return transactions.read(()->{
            var order=owned(uid,no);
            var values=OrderItemRules.checked(order,items.selectActiveByOrderId(order.getId())).stream()
                .map(item->new OrderStatusItemVO(item.getProductName(),item.getQuantity())).toList();
            return new OrderStatusVO(no,order.getStatus(),status(order).description(),order.getLogisticsCompany(),order.getTrackingNo(),values);
        });
    }
    private Orders owned(Long uid,String no) {
        users.check(uid); var order=orders.selectOwned(uid,no);
        if (order==null) { throw new BusinessException(404,"订单不存在"); }
        return order;
    }
    private OrderStatus status(Orders row) {
        if (row.getStatus()==null) { throw new BusinessException(500,"订单状态异常"); }
        return OrderStatus.fromCode(row.getStatus());
    }
}
