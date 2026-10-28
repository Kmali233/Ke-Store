package com.xiaoke.order.service;
import com.xiaoke.common.exception.*;
import com.xiaoke.order.adapter.product.ProductDetailHttpClient;
import com.xiaoke.order.config.OrderProperties;
import com.xiaoke.order.dto.*;
import com.xiaoke.order.vo.OrderCreateVO;
import com.xiaoke.order.idempotency.OrderIdempotency;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.sql.SQLException;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** 按日JVM锁覆盖创建事务的提交/回滚；HTTP在锁外，仅单号索引撞号允许重试。 */
@Service
public class OrderCommandFacade implements OrderCommandService {
    private static final Pattern ORDER_KEY=Pattern.compile("for key ['\\x60](?:[^'\\x60]+\\.)?uk_order_no['\\x60]\\s*$");
    private final ConcurrentMap<LocalDate,ReentrantLock> dayLocks=new ConcurrentHashMap<>();
    private final OrderInput input;
    private final OrderUsers users;
    private final ProductDetailHttpClient http;
    private final OrderTransactionService writes;
    private final OrderTransactions transactions;
    private final OrderIdempotency idempotency;
    private final Clock clock;
    private final ZoneId zone;
    public OrderCommandFacade(OrderInput input,OrderUsers users,ProductDetailHttpClient http,OrderTransactionService writes,
            OrderTransactions transactions,Clock clock,OrderProperties properties,OrderIdempotency idempotency) {
        this.input=input; this.users=users; this.http=http; this.writes=writes; this.transactions=transactions;
        this.clock=clock; this.zone=ZoneId.of(properties.getZoneId());
        this.idempotency=idempotency;
    }
    public OrderCreateVO create(Long uid,OrderCreateDTO dto) {
        return create(uid,dto,null);
    }
    public OrderCreateVO creationResult(Long uid,String key) {
        transactions.outsideTransaction(); users.check(uid);
        return idempotency.lookup(uid,key);
    }
    public OrderCreateVO create(Long uid,OrderCreateDTO dto,String key) {
        LocalDate day=LocalDate.now(clock.withZone(zone));
        transactions.outsideTransaction(); input.check(dto); users.check(uid);
        // 整理购买标记，计算请求内容的摘要
        var request=key==null ? null : idempotency.request(key,dto);
        // 查询以前是否有对应的成功回执
        var previous=idempotency.replay(uid,request,false);
        // 找到并匹配成功，就直接返回原结果
        if (previous!=null) { return previous; }
        try {
            var ids=dto.getItems().stream().allMatch(item->item.getSkuId()==null)
                    ? dto.getItems().stream().map(OrderItemCreateDTO::getProductId).toList() : writes.productIds(dto);
            http.preread(ids.stream().distinct().sorted().toList());
        } catch (RuntimeException failure) {
            // Another process may have committed while this request was doing HTTP prereads.
            var concurrent=idempotency.replay(uid,request,false);
            if (concurrent!=null) { return concurrent; }
            throw failure;
        }
        var lock=dayLocks.computeIfAbsent(day,ignored->new ReentrantLock());
        try { lock.lockInterruptibly(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new BusinessException(503,"服务暂不可用"); }
        try {
            for (int attempt=1;attempt<=3;attempt++) {
                try { return request==null ? writes.createOnce(uid,dto,day) : writes.createOnce(uid,dto,day,request); }
                catch (DuplicateKeyException duplicate) {
                    if (!orderNumberCollision(duplicate)) { throw duplicate; }
                    if (attempt==3) { throw new BusinessException(409,"订单号分配冲突"); }
                }
            }
            throw new IllegalStateException("Unreachable order allocation");
        } finally { lock.unlock(); }
    }
    public void pay(Long uid,String no) { input.orderNo(no); writes.payOnce(uid,no); }
    public void ship(String no,OrderShipDTO dto) { input.orderNo(no); input.check(dto); writes.shipOnce(no,dto); }
    public void cancel(Long uid,String no) { input.orderNo(no); writes.cancelOnce(uid,no); }
    static boolean orderNumberCollision(Throwable failure) {
        if (DatabaseFailures.outcomeUnknown(failure)) { return false; }
        return DatabaseFailures.causes(failure).stream().anyMatch(cause->cause instanceof SQLException sql
                && sql.getErrorCode()==1062 && "23000".equals(sql.getSQLState()) && sql.getMessage()!=null
                && ORDER_KEY.matcher(sql.getMessage()).find());
    }
}
