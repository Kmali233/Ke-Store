package com.xiaoke.order.service;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.adapter.product.*;
import com.xiaoke.order.dto.OrderItemCreateDTO;
import com.xiaoke.order.entity.OrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class OrderSkus {
    private final OrderSkuMapper mapper;
    public record Selection(Long productId,Long skuId,int quantity) { }
    public List<Selection> resolve(List<OrderItemCreateDTO> items) {
        var result=new ArrayList<Selection>();
        var ids=new HashSet<Long>();
        for(var item:items) {
            var row=item.getSkuId()==null ? mapper.defaultSku(item.getProductId()) : mapper.find(item.getSkuId());
            if(row==null)
                throw new BusinessException(404,"SKU不存在或默认SKU未迁移");

            if(!ids.add(row.getId()))
                throw new BusinessException(400,"同一SKU不能重复下单");

            result.add(new Selection(row.getProductId(),row.getId(),item.getQuantity()));
        }
        return result.stream().sorted(Comparator.comparing(Selection::skuId)).toList();
    }
    public Long historicalSku(OrderItem item) {
        if(item.getSkuId()!=null) return item.getSkuId();
        var row=mapper.defaultSku(item.getProductId());
        if(row==null) throw new BusinessException(500,"历史订单默认SKU缺失");return row.getId();
    }
    public OrderSkuRow lock(Long id,Long pid,boolean requireAvailable) {
        var row=mapper.lock(id);
        if(row==null || !Objects.equals(row.getProductId(),pid))
            throw new BusinessException(500,"订单SKU数据异常");
        if(requireAvailable && (!Integer.valueOf(0).equals(row.getDeleted()) || !Integer.valueOf(1).equals(row.getStatus())))
            throw new BusinessException(404,"SKU不可用");
        if(row.getStock()==null || row.getStock()<0 || row.getSales()==null || row.getSales()<0)
            throw new BusinessException(500,"SKU计数异常");
        return row;
    }
    public void deduct(Long id,int q) {
        if(mapper.deduct(id,q)!=1)
            throw new BusinessException(409,"SKU库存冲突");
    }
    public void restore(Long id,int q) {
        if(mapper.restore(id,q)!=1)
            throw new BusinessException(409,"SKU恢复冲突");
    }
}
