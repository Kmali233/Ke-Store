package com.xiaoke.order.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaoke.order.entity.OrderItem;
import org.apache.ibatis.annotations.*;
import java.util.List;
public interface OrderItemMapper extends BaseMapper<OrderItem> {
    @Select("SELECT * FROM t_order_item WHERE order_id=#{id} AND deleted=0 ORDER BY product_id ASC,id ASC")
    List<OrderItem> selectActiveByOrderId(@Param("id") Long id);
    @Select("SELECT * FROM t_order_item WHERE order_id=#{id} ORDER BY product_id ASC,id ASC")
    List<OrderItem> selectAllByOrderId(@Param("id") Long id);
    @Insert("""
        <script>
        INSERT INTO t_order_item(order_id,order_no,product_id,product_name,product_image,price,quantity,deleted)
        VALUES <foreach collection="rows" item="r" separator=",">
        (#{r.orderId},#{r.orderNo},#{r.productId},#{r.productName},#{r.productImage},#{r.price},#{r.quantity},0)
        </foreach>
        </script>
        """)
    int insertBatch(@Param("rows") List<OrderItem> rows);
}
