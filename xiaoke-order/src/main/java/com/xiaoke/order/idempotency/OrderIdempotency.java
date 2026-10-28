package com.xiaoke.order.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.dto.*;
import com.xiaoke.order.vo.OrderCreateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.security.*;
import java.util.*;
import static com.xiaoke.order.service.OrderInput.rows;

/** 校验键、计算内容摘要、比较并返回回执 */
@Component @RequiredArgsConstructor
public class OrderIdempotency {
    private final OrderIdempotencyMapper mapper;
    private final ObjectMapper json;
    public record Request(String key,int version,String hash) { }

    public String key(String key) {
        if (key==null || !key.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(400,"Idempotency-Key格式不合法");
        }
        return key;
    }

     /** 计算请求摘要,返回幂等键、版本号、内容摘要 */
    public Request request(String key,OrderCreateDTO dto) {
        key(key);
        // Fixed positional arrays avoid map/JSON property-order and delimiter ambiguities.
        boolean skuRequest=dto.getItems().stream().anyMatch(item->item.getSkuId()!=null);
        int version=skuRequest?2:1;
        Object products;
        if(!skuRequest) {
            // Keep the exact v1 contract for every legacy productId request, including stored receipts.
            products=dto.getItems().stream().sorted(Comparator.comparing(OrderItemCreateDTO::getProductId))
                    .map(item->List.of(item.getProductId(),item.getQuantity())).toList();
        } else {
            products=dto.getItems().stream().sorted(Comparator.comparing((OrderItemCreateDTO i)->i.getSkuId()==null?0:1)
                    .thenComparing(i->i.getSkuId()==null?i.getProductId():i.getSkuId()))
                    .map(i->List.of(i.getSkuId()==null?"product":"sku",i.getSkuId()==null?i.getProductId():i.getSkuId(),i.getQuantity())).toList();
        }
        Object shipping=dto.getAddressId()!=null ? List.of("address",dto.getAddressId())
                : List.of("manual",dto.getReceiverName(),dto.getReceiverPhone(),dto.getReceiverAddress());
        try {
            byte[] canonical=json.writeValueAsBytes(Arrays.asList(version,products,shipping,dto.getRemark()));
            return new Request(key,version,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)));
        } catch (JsonProcessingException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Cannot hash order request",impossible);
        }
    }


    /** 校验请求摘要 */
    public OrderCreateVO replay(Long uid,Request request,boolean locked) {
        if (request==null) { return null; }
        var row=locked ? mapper.lock(uid,request.key()) : mapper.find(uid,request.key());
        if (row==null) { return null; }
        if (!Objects.equals(row.getHashVersion(),request.version()) || !Objects.equals(row.getRequestHash(),request.hash())) {
            throw new BusinessException(409,"幂等键已用于其他下单请求");
        }
        return result(row);
    }

    public OrderCreateVO lookup(Long uid,String key) {
        var row=mapper.find(uid,key(key));
        if (row==null) { throw new BusinessException(404,"尚未查到成功下单结果，请保留原幂等键"); }
        return result(row);
    }

    public void save(Long uid,Request request,OrderCreateVO result) {
        if (request!=null) { rows(mapper.insert(uid,request,result),1); }
    }

    private OrderCreateVO result(OrderIdempotencyRow row) {
        return new OrderCreateVO(row.getOrderNo(),row.getResultStatus(),row.getTotalAmount());
    }
}
