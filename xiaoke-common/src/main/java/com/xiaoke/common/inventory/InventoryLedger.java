package com.xiaoke.common.inventory;

import com.xiaoke.common.auth.admin.AdminContext;
import com.xiaoke.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared SQL journal; every call joins the owning business transaction and SKU row lock. */
@Component @RequiredArgsConstructor
public class InventoryLedger {
    private final JdbcTemplate jdbc;
    public enum Event { OPENING, INBOUND, ADJUSTMENT, ORDER_OCCUPY, CANCEL_RESTORE, REFUND_RESTORE }

    public void opening(long skuId,int stock,int sales) {
        transaction();
        insert(skuId,Event.OPENING,"OPENING",stock,sales,0,stock);
    }
    /** Call after guarded counter SQL, while still holding the SKU lock. */
    public void changed(long skuId,Event event,String businessNo,int quantity,int salesQuantity) {
        transaction();
        if(event==Event.OPENING) throw new IllegalArgumentException("Use opening");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM t_inventory_ledger WHERE sku_id=? AND event_type='OPENING'",Long.class,skuId)!=1)
            throw new BusinessException(500,"库存期初缺失，请核对迁移");
        int after=jdbc.queryForObject("SELECT stock FROM t_product_sku WHERE id=?",Integer.class,skuId);
        long before=(long)after-quantity;
        if(before<0 || before>Integer.MAX_VALUE) throw new BusinessException(409,"库存数值溢出");
        insert(skuId,event,businessNo,quantity,salesQuantity,(int)before,after);
    }
    private void insert(long skuId,Event event,String no,int q,int sq,int before,int after) {
        Long actor=AdminContext.current().map(p->p.adminId()).orElse(null);
        if(jdbc.update("""
            INSERT INTO t_inventory_ledger(sku_id,event_type,business_no,quantity,sales_quantity,before_stock,after_stock,actor_id)
            VALUES(?,?,?,?,?,?,?,?)
            """,skuId,event.name(),no,q,sq,before,after,actor)!=1) throw new IllegalStateException("Inventory journal insert failed");
    }
    private void transaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Inventory requires the owning writable transaction");
    }
}
