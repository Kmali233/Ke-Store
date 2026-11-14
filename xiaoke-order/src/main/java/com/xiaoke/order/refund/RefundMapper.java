package com.xiaoke.order.refund;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface RefundMapper {
    record Summary(String orderNo, String refundNo, BigDecimal amount, RefundOrder.State status,
                   LocalDateTime createdAt, LocalDateTime updatedAt) {}

    @Select("""
        SELECT order_id,refund_no,amount,status,created_at,updated_at
        FROM t_order_refund WHERE order_id=#{id}
        """)
    RefundOrder find(long id);

    @Select("""
        <script>
        SELECT o.order_no,r.refund_no,r.amount,r.status,r.created_at,r.updated_at
        FROM t_order_refund r JOIN t_orders o ON o.id=r.order_id AND o.deleted=0
        <where>
            <if test="status != null">r.status=#{status}</if>
            <if test="no != null">AND o.order_no=#{no}</if>
        </where>
        ORDER BY r.created_at DESC,r.order_id DESC LIMIT 50 OFFSET #{offset}
        </script>
        """)
    List<Summary> list(@Param("status") RefundOrder.State status, @Param("no") String orderNo,
                       @Param("offset") long offset);

    @Insert("""
        INSERT INTO t_order_refund(order_id,refund_no,amount,status)
        VALUES(#{id},#{no},#{amount},'REQUESTED')
        """)
    int request(@Param("id") long id, @Param("no") String refundNo, @Param("amount") BigDecimal amount);

    @Update("UPDATE t_order_refund SET status=#{after} WHERE order_id=#{id} AND status=#{before}")
    int transition(@Param("id") long id, @Param("before") RefundOrder.State before,
                   @Param("after") RefundOrder.State after);
}
