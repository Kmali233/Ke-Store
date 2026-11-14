package com.xiaoke.order.service;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.inventory.InventoryLedger.Event;
import com.xiaoke.order.adapter.category.OrderCategoryReadAdapter;
import com.xiaoke.order.adapter.product.*;
import com.xiaoke.order.allocator.OrderNoAllocator;
import com.xiaoke.order.dto.*;
import com.xiaoke.order.config.OrderProperties;
import com.xiaoke.order.entity.*;
import com.xiaoke.order.enums.OrderStatus;
import com.xiaoke.order.mapper.*;
import com.xiaoke.order.payment.MockPaymentAdapter;
import com.xiaoke.order.vo.OrderCreateVO;
import com.xiaoke.order.idempotency.OrderIdempotency;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.xiaoke.order.service.OrderInput.rows;

/** 所有写SQL共用一个数据源连接，事务边界包含提交和回滚。 */
@Service @RequiredArgsConstructor
public class OrderTransactionService {
    private final OrderTransactions transactions;
    private final OrderProperties properties;
    private final OrderUsers users;
    private final OrderCategoryReadAdapter categories;
    private final OrderProductMapper products;
    private final OrdersMapper orders;
    private final OrderItemMapper items;
    private final OrderNoAllocator allocator;
    private final OrderIdempotency idempotency;
    private final OrderSkus skus;
    private final CartService cart;
    private final PaymentOrderMapper payments;
    private final ObjectProvider<MockPaymentAdapter> mockPayment;
    public List<Long> productIds(OrderCreateDTO dto) {
        return skus.resolve(dto.getItems()).stream().map(OrderSkus.Selection::productId).distinct().sorted().toList();
    }
    public OrderCreateVO createOnce(Long uid,OrderCreateDTO dto,LocalDate day) {
        return createOnce(uid,dto,day,null);
    }
    public OrderCreateVO createOnce(Long uid,OrderCreateDTO dto,LocalDate day,OrderIdempotency.Request request) {
        return createOnce(uid,dto,day,request,false);
    }
    public OrderCreateVO createCartOnce(Long uid,OrderCreateDTO dto,LocalDate day,OrderIdempotency.Request request) {
        return createOnce(uid,dto,day,request,true);
    }
    private OrderCreateVO createOnce(Long uid,OrderCreateDTO dto,LocalDate day,OrderIdempotency.Request request,boolean fromCart) {
        return transactions.create(()->{
            users.lock(uid);
            // All order creators lock the user first, serializing one user's keys across JVMs.
            var previous=idempotency.replay(uid,request,true);
            if (previous!=null) { return previous; }
            if (fromCart) { cart.requireSelection(uid,dto.getItems()); }
            var inputs=skus.resolve(dto.getItems());
            var productIds=inputs.stream().map(OrderSkus.Selection::productId).distinct().sorted().toList();
            var discovery=categories.discover(productIds);
            categories.lockAndVerify(discovery);
            var lockedProducts=new HashMap<Long,OrderProductRow>();
            for(Long pid:productIds) {
                var product=products.selectForUpdate(pid);categories.verifyProduct(discovery,product);
                lockedProducts.put(pid,product);
            }
            List<OrderItem> snapshots=new ArrayList<>();
            BigDecimal total=new BigDecimal("0.00");
            for (var input:inputs) {
                var product=lockedProducts.get(input.productId());
                var sku=skus.lock(input.skuId(),input.productId(),true);
                int quantity=input.quantity();
                if (sku.getStock()<quantity || (long)sku.getSales()+quantity>Integer.MAX_VALUE) { throw conflict(); }
                var price=OrderMoney.price(sku.getPrice());
                total=OrderMoney.bounded(total.add(OrderMoney.amount(price,quantity,400)),400);
                var item=new OrderItem();
                item.setProductId(product.getId()); item.setProductName(product.getName());
                item.setProductImage(sku.getImage()==null?product.getImage():sku.getImage());
                item.setSkuId(sku.getId());item.setSkuName(sku.getName());item.setSkuSpec(sku.getSpecJson());
                item.setPrice(price); item.setQuantity(quantity); item.setDeleted(0); snapshots.add(item);
            }
            String no=allocator.next(day);
            Orders order=new Orders();
            order.setOrderNo(no); order.setUserId(uid); order.setTotalAmount(total); order.setStatus(OrderStatus.PENDING_PAYMENT.code());
            order.setRemark(dto.getRemark()); order.setDeleted(0);
            order.setPaymentExpiresAt(orders.utcNow().plusMinutes(properties.getPaymentTimeoutMinutes()));
            order.setCloseNextAttemptAt(order.getPaymentExpiresAt());
            rows(orders.insert(order),1);
            // Reserve the unique order number before journal events so the existing number-collision retry remains authoritative.
            for (var item:snapshots) { skus.deduct(item.getSkuId(),item.getQuantity(),no); }
            for (var item:snapshots) { item.setOrderId(order.getId()); item.setOrderNo(no); }
            rows(items.insertBatch(snapshots),snapshots.size());
            var result=new OrderCreateVO(no,order.getStatus(),total);
            idempotency.save(uid,request,result);
            if (fromCart) { cart.clearSelection(uid,dto.getItems().size()); }
            return result;
        });
    }
    public void payOnce(Long uid,String no) {
        var mock=mockPayment.getIfAvailable();
        if (mock==null) { throw new BusinessException(403,"模拟支付通道暂未启用"); }
        transactions.write(()->{
            users.lock(uid);
            var order=requireOrder(orders.lockOwned(uid,no));
            if (status(order)!=OrderStatus.PENDING_PAYMENT || !order.acceptsPayment(orders.utcNow()) || payments.findByOrderId(order.getId())!=null
                    || !mock.pay(order)) { throw conflict(); }
            return null;
        });
    }
    void cancelAndRestore(Orders order) {
        if (orders.casCancel(order.getId(), order.getUserId()) != 1) { throw conflict(); }
        restoreInventory(order, Event.CANCEL_RESTORE);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refundAndRestore(Orders order) {
        rows(orders.closeRefunded(order.getId()), 1);
        restoreInventory(order, Event.REFUND_RESTORE);
    }

    private void restoreInventory(Orders order, Event event) {
        var snapshots=OrderItemRules.checked(order,items.selectAllByOrderId(order.getId()));
        for (Long pid:snapshots.stream().map(OrderItem::getProductId).distinct().sorted().toList()) {
            var product=products.selectForUpdate(pid);
            if (product==null) { throw new BusinessException(500,"原商品缺失"); }
        }
        var restores=new TreeMap<Long,OrderItem>();
        for(var item:snapshots) {
            Long sid=skus.historicalSku(item);
            if(restores.put(sid,item)!=null) throw new BusinessException(500,"重复的恢复SKU");
        }
        for(var entry:restores.entrySet()) {
            var item=entry.getValue();var sku=skus.lock(entry.getKey(),item.getProductId(),false);
            if ((long)sku.getStock()+item.getQuantity()>Integer.MAX_VALUE || sku.getSales()<item.getQuantity()) { throw conflict(); }
        }
        for (var entry:restores.entrySet()) {skus.restore(entry.getKey(),entry.getValue().getQuantity(),order.getOrderNo(),event);}
    }
    private Orders requireOrder(Orders row) { if (row==null) { throw new BusinessException(404,"订单不存在"); } return row; }
    private OrderStatus status(Orders row) {
        if (row.getStatus()==null) { throw new BusinessException(500,"订单状态异常"); }
        return OrderStatus.fromCode(row.getStatus());
    }
    private BusinessException conflict() { return new BusinessException(409,"订单或商品状态冲突"); }
}
