package com.xiaoke.order.mapper;

import com.xiaoke.order.payment.PaymentOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PaymentOrderMapper {
    @Select("""
        SELECT order_id, payment_no, channel, app_id, seller_id, total_amount, status, trade_no
        FROM t_payment_order WHERE order_id=#{orderId}
        """)
    PaymentOrder findByOrderId(Long orderId);

    @Select("SELECT order_id FROM t_payment_order WHERE payment_no=#{paymentNo}")
    Long findOrderId(String paymentNo);

    @Select("SELECT order_id FROM t_payment_order WHERE channel=#{channel} AND trade_no=#{tradeNo}")
    Long findTradeOwner(@Param("channel") String channel, @Param("tradeNo") String tradeNo);

    @Insert("""
        INSERT INTO t_payment_order (order_id,payment_no,channel,app_id,seller_id,total_amount,status)
        VALUES (#{orderId},#{paymentNo},#{channel},#{appId},#{sellerId},#{totalAmount},#{status})
        """)
    int insert(PaymentOrder payment);

    @Update("""
        UPDATE t_payment_order SET status='SUCCEEDED',trade_no=#{tradeNo},pay_time=CURRENT_TIMESTAMP
        WHERE order_id=#{orderId} AND status='PENDING'
        """)
    int succeed(@Param("orderId") Long orderId, @Param("tradeNo") String tradeNo);

    @Update("UPDATE t_payment_order SET status='CLOSED' WHERE order_id=#{orderId} AND status='PENDING'")
    int close(Long orderId);
}
