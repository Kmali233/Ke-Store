package com.xiaoke.order.idempotency;

import com.xiaoke.order.vo.OrderCreateVO;
import org.apache.ibatis.annotations.*;

/**
 * 查询、插入回执的 SQL
 */
public interface OrderIdempotencyMapper {
    String LOOKUP="SELECT hash_version,request_hash,order_no,result_status,total_amount FROM t_order_idempotency "
            +"WHERE user_id=#{uid} AND idempotency_key=#{key}";
    @Select(LOOKUP)
    OrderIdempotencyRow find(@Param("uid") Long uid,@Param("key") String key);

    @Select(LOOKUP+" FOR UPDATE")
    OrderIdempotencyRow lock(@Param("uid") Long uid,@Param("key") String key);

    @Insert("""
        INSERT INTO t_order_idempotency
            (user_id,idempotency_key,hash_version,request_hash,order_no,result_status,total_amount)
        VALUES (#{uid},#{request.key},#{request.version},#{request.hash},
                #{result.orderNo},#{result.status},#{result.totalAmount})
        """)
    int insert(@Param("uid") Long uid,@Param("request") OrderIdempotency.Request request,
               @Param("result") OrderCreateVO result);
}
