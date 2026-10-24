package com.xiaoke.order.service;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.adapter.address.*;
import com.xiaoke.order.adapter.category.OrderCategoryReadAdapter;
import com.xiaoke.order.adapter.product.*;
import com.xiaoke.order.allocator.OrderNoAllocator;
import com.xiaoke.order.dto.*;
import com.xiaoke.order.entity.*;
import com.xiaoke.order.enums.OrderStatus;
import com.xiaoke.order.mapper.*;
import com.xiaoke.order.vo.OrderCreateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.xiaoke.order.service.OrderInput.rows;

/** 所有写SQL共用一个数据源连接，事务边界包含提交和回滚。 */
@Service @RequiredArgsConstructor
public class OrderTransactionService {
    private final OrderTransactions transactions;
    private final OrderUsers users;
    private final OrderAddressReadAdapter addresses;
    private final OrderCategoryReadAdapter categories;
    private final OrderProductMapper products;
    private final OrdersMapper orders;
    private final OrderItemMapper items;
    private final OrderNoAllocator allocator;
    public OrderCreateVO createOnce(Long uid,OrderCreateDTO dto,LocalDate day) {
        return transactions.create(()->{
            users.lock(uid);
            ShippingSnapshot shipping=dto.getAddressId()==null
                    ? new ShippingSnapshot(dto.getReceiverName(),dto.getReceiverPhone(),dto.getReceiverAddress())
                    : addresses.readOwned(uid,dto.getAddressId());
            var inputs=dto.getItems().stream().sorted(Comparator.comparing(OrderItemCreateDTO::getProductId)).toList();
            var discovery=categories.discover(inputs.stream().map(OrderItemCreateDTO::getProductId).toList());
            categories.lockAndVerify(discovery);
            List<OrderItem> snapshots=new ArrayList<>();
            BigDecimal total=new BigDecimal("0.00");
            for (var input:inputs) {
                var product=products.selectForUpdate(input.getProductId());
                categories.verifyProduct(discovery,product); requireCounters(product);
                int quantity=input.getQuantity();
                if (product.getStock()<quantity || (long)product.getSales()+quantity>Integer.MAX_VALUE) { throw conflict(); }
                var price=OrderMoney.price(product.getPrice());
                total=OrderMoney.bounded(total.add(OrderMoney.amount(price,quantity,400)),400);
                var item=new OrderItem();
                item.setProductId(product.getId()); item.setProductName(product.getName()); item.setProductImage(product.getImage());
                item.setPrice(price); item.setQuantity(quantity); item.setDeleted(0); snapshots.add(item);
            }
            String no=allocator.next(day);
            for (var item:snapshots) { if (products.deduct(item.getProductId(),item.getQuantity())!=1) { throw conflict(); } }
            Orders order=new Orders();
            order.setOrderNo(no); order.setUserId(uid); order.setTotalAmount(total); order.setStatus(OrderStatus.PENDING_PAYMENT.code());
            order.setReceiverName(shipping.receiverName()); order.setReceiverPhone(shipping.receiverPhone());
            order.setReceiverAddress(shipping.receiverAddress()); order.setRemark(dto.getRemark()); order.setDeleted(0);
            rows(orders.insert(order),1);
            for (var item:snapshots) { item.setOrderId(order.getId()); item.setOrderNo(no); }
            rows(items.insertBatch(snapshots),snapshots.size());
            return new OrderCreateVO(no,order.getStatus(),total);
        });
    }
    public void payOnce(Long uid,String no) {
        transactions.write(()->{
            users.lock(uid);
            var order=requireOrder(orders.lockOwned(uid,no));
            if (status(order)!=OrderStatus.PENDING_PAYMENT || orders.casPay(order.getId(),uid)!=1) { throw conflict(); }
            return null;
        });
    }
    public void shipOnce(String no,OrderShipDTO dto) {
        transactions.write(()->{
            var order=requireOrder(orders.lockByNo(no)); var status=status(order);
            if ((status!=OrderStatus.PAID && status!=OrderStatus.PREPARING)
                    || orders.casShip(order.getId(),dto.getLogisticsCompany(),dto.getTrackingNo())!=1) { throw conflict(); }
            return null;
        });
    }
    public void cancelOnce(Long uid,String no) {
        transactions.write(()->{
            users.lock(uid);
            var order=requireOrder(orders.lockOwned(uid,no)); var status=status(order);
            if (status!=OrderStatus.PENDING_PAYMENT && status!=OrderStatus.PAID && status!=OrderStatus.PREPARING) { throw conflict(); }
            var snapshots=OrderItemRules.checked(order,items.selectAllByOrderId(order.getId()));
            for (var item:snapshots) {
                var product=products.selectForUpdate(item.getProductId());
                if (product==null) { throw new BusinessException(500,"原商品缺失"); }
                requireCounters(product);
                if ((long)product.getStock()+item.getQuantity()>Integer.MAX_VALUE || product.getSales()<item.getQuantity()) { throw conflict(); }
            }
            if (orders.casCancel(order.getId(),uid)!=1) { throw conflict(); }
            for (var item:snapshots) { if (products.restore(item.getProductId(),item.getQuantity())!=1) { throw conflict(); } }
            return null;
        });
    }
    private void requireCounters(OrderProductRow row) {
        if (row.getStock()==null || row.getSales()==null || row.getStock()<0 || row.getSales()<0) { throw new BusinessException(500,"商品计数异常"); }
    }
    private Orders requireOrder(Orders row) { if (row==null) { throw new BusinessException(404,"订单不存在"); } return row; }
    private OrderStatus status(Orders row) {
        if (row.getStatus()==null) { throw new BusinessException(500,"订单状态异常"); }
        return OrderStatus.fromCode(row.getStatus());
    }
    private BusinessException conflict() { return new BusinessException(409,"订单或商品状态冲突"); }
}
