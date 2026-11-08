package com.xiaoke.product.service;

import com.xiaoke.audit.*;
import com.xiaoke.common.auth.admin.AdminContext;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.inventory.InventoryLedger;
import com.xiaoke.common.inventory.InventoryLedger.Event;
import com.xiaoke.product.dto.*;
import com.xiaoke.product.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class InventoryService {
    private final JdbcTemplate jdbc;
    private final ProductMapper products;
    private final SkuMapper skus;
    private final CatalogTransactions transactions;
    private final CatalogValidation validation;
    private final InventoryLedger ledger;
    private final AuditWriter audit;

    public record Adjustment(long id,String businessNo,long skuId,String type,int quantity,String reason) { }
    public Adjustment adjust(Long pid,Long sid,InventoryAdjustmentDTO dto) {
        validation.id(pid);validation.id(sid);validation.check(dto);
        if(dto.getQuantity()==0 || (dto.getType().equals("INBOUND") && dto.getQuantity()<0)
                || dto.getReason().chars().anyMatch(Character::isISOControl)) throw new BusinessException(400,"调整数量或原因不合法");
        try {
            return transactions.write(()->{
                // Same shared order as checkout: product before SKU; never acquire category/user locks afterwards.
                var product=products.lockById(pid);
                if(product==null) throw new BusinessException(404,"商品不存在");
                var sku=skus.lock(pid,sid);
                if(sku==null) throw new BusinessException(404,"SKU不存在");
                var existing=jdbc.query("SELECT id,business_no,sku_id,event_type,quantity,reason FROM t_inventory_adjustment WHERE business_no=?",
                    (rs,n)->new Adjustment(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getInt(5),rs.getString(6)),dto.getBusinessNo());
                if(!existing.isEmpty()) {
                    var old=existing.get(0);
                    if(old.skuId()!=sid || !old.type().equals(dto.getType()) || old.quantity()!=dto.getQuantity() || !old.reason().equals(dto.getReason()))
                        throw new BusinessException(409,"业务号已用于其他调整");
                    return old;
                }
                if(product.getDeleted()!=0 || sku.getDeleted()!=0) throw new BusinessException(404,"商品或SKU已删除");
                long after=(long)sku.getStock()+dto.getQuantity();
                if(sku.getStock()<0 || after<0 || after>Integer.MAX_VALUE) throw new BusinessException(409,"库存不足或数值溢出");
                Long actor=AdminContext.current().map(p->p.adminId()).orElse(null);
                jdbc.update("INSERT INTO t_inventory_adjustment(business_no,sku_id,event_type,quantity,reason,actor_id) VALUES(?,?,?,?,?,?)",
                    dto.getBusinessNo(),sid,dto.getType(),dto.getQuantity(),dto.getReason(),actor);
                if(jdbc.update("UPDATE t_product_sku SET stock=? WHERE id=? AND stock=?",after,sid,sku.getStock())!=1)
                    throw new BusinessException(409,"库存冲突");
                ledger.changed(sid,Event.valueOf(dto.getType()),dto.getBusinessNo(),dto.getQuantity(),0);
                audit.record(AuditAction.INVENTORY_ADJUST,sid,AuditChanges.start().put(AuditChanges.Field.STOCK,sku.getStock(),(int)after));
                long id=jdbc.queryForObject("SELECT id FROM t_inventory_adjustment WHERE business_no=?",Long.class,dto.getBusinessNo());
                return new Adjustment(id,dto.getBusinessNo(),sid,dto.getType(),dto.getQuantity(),dto.getReason());
            });
        } catch(DuplicateKeyException conflict) { throw new BusinessException(409,"业务号或库存事件重复，请核对后重试"); }
    }
    public Map<String,Object> page(Long pid,Long sid,InventoryQuery query) {
        validation.id(pid);validation.id(sid);validation.check(query);query.validatePage();
        return transactions.read(()->{
            requireOwned(pid,sid);
            var args=new ArrayList<Object>();args.add(sid);
            String where=" WHERE l.sku_id=?";
            if(query.getType()!=null) {where+=" AND l.event_type=?";args.add(query.getType());}
            if(query.getBusinessNo()!=null) {where+=" AND l.business_no=?";args.add(query.getBusinessNo());}
            long total=jdbc.queryForObject("SELECT COUNT(*) FROM t_inventory_ledger l"+where,Long.class,args.toArray());
            args.add(query.getPageSize());args.add((query.getPageNum()-1)*query.getPageSize());
            var records=jdbc.queryForList("""
                SELECT l.id,l.sku_id AS skuId,l.event_type AS type,l.business_no AS businessNo,
                l.quantity,l.sales_quantity AS salesQuantity,l.before_stock AS beforeStock,l.after_stock AS afterStock,
                l.actor_id AS actorId,l.created_at AS createdAt,a.id AS adjustmentId,a.reason
                FROM t_inventory_ledger l LEFT JOIN t_inventory_adjustment a
                ON a.sku_id=l.sku_id AND a.business_no=l.business_no AND a.event_type=l.event_type
                """+where+" ORDER BY l.id DESC LIMIT ? OFFSET ?",args.toArray());
            return Map.of("total",total,"records",records,"pageNum",query.getPageNum(),"pageSize",query.getPageSize());
        });
    }
    public Map<String,Object> balance(Long pid,Long sid) {
        validation.id(pid);validation.id(sid);
        return transactions.read(()->{
            requireOwned(pid,sid);
            var result=jdbc.queryForMap("""
                SELECT s.id AS skuId,s.stock,s.sales,COALESCE(SUM(l.quantity),0) AS ledgerStock,
                COALESCE(SUM(l.sales_quantity),0) AS ledgerSales,
                SUM(CASE WHEN l.event_type='OPENING' THEN 1 ELSE 0 END) AS openingCount,
                (COUNT(CASE WHEN l.event_type='OPENING' THEN 1 END)=1 AND s.stock=COALESCE(SUM(l.quantity),0)
                    AND s.sales=COALESCE(SUM(l.sales_quantity),0)) AS consistent
                FROM t_product_sku s LEFT JOIN t_inventory_ledger l ON l.sku_id=s.id WHERE s.id=? GROUP BY s.id,s.stock,s.sales
                """,sid);
            Object consistent=result.get("consistent");
            result.put("consistent",Boolean.TRUE.equals(consistent) || consistent instanceof Number n && n.intValue()==1);
            return result;
        });
    }
    private void requireOwned(Long pid,Long sid) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM t_product_sku WHERE id=? AND product_id=?",Long.class,sid,pid)!=1)
            throw new BusinessException(404,"SKU不存在");
    }
}
