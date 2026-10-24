package com.xiaoke.order.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaoke.order.entity.Orders;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface OrdersMapper extends BaseMapper<Orders> {
    @Select("SELECT * FROM t_orders WHERE order_no=#{no} AND user_id=#{uid} AND deleted=0")
    Orders selectOwned(@Param("uid") Long uid,@Param("no") String no);
    @Select("SELECT * FROM t_orders WHERE order_no=#{no} AND user_id=#{uid} AND deleted=0 FOR UPDATE")
    Orders lockOwned(@Param("uid") Long uid,@Param("no") String no);
    @Select("SELECT * FROM t_orders WHERE order_no=#{no} AND deleted=0 FOR UPDATE")
    Orders lockByNo(@Param("no") String no);
    String OWNED="WHERE user_id=#{uid} AND deleted=0 <if test='status != null'>AND status=#{status}</if>";
    @Select("<script>SELECT COUNT(*) FROM t_orders "+OWNED+"</script>")
    long countOwned(@Param("uid") Long uid,@Param("status") Integer status);
    @Select("<script>SELECT order_no,total_amount,status,create_time FROM t_orders "+OWNED+
        " ORDER BY create_time DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Orders> pageOwned(@Param("uid") Long uid,@Param("status") Integer status,@Param("size") long size,@Param("offset") long offset);
    @Select("SELECT MAX(order_no) FROM t_orders WHERE order_no BETWEEN #{lower} AND #{upper} AND CHAR_LENGTH(order_no)=14")
    String maxOrderNo(@Param("lower") String lower,@Param("upper") String upper);
    @Select("SELECT COUNT(*) FROM t_orders WHERE order_no LIKE #{prefix} AND (CHAR_LENGTH(order_no)!=14 OR order_no NOT REGEXP '^[0-9]{14}$')")
    long countMalformed(@Param("prefix") String prefix);
    @Update("UPDATE t_orders SET status=1,pay_time=CURRENT_TIMESTAMP WHERE id=#{id} AND user_id=#{uid} AND deleted=0 AND status=0")
    int casPay(@Param("id") Long id,@Param("uid") Long uid);
    @Update("""
        UPDATE t_orders SET status=3,logistics_company=#{company},tracking_no=#{tracking},ship_time=CURRENT_TIMESTAMP
        WHERE id=#{id} AND deleted=0 AND status IN(1,2)
        """)
    int casShip(@Param("id") Long id,@Param("company") String company,@Param("tracking") String tracking);
    @Update("UPDATE t_orders SET status=5 WHERE id=#{id} AND user_id=#{uid} AND deleted=0 AND status IN(0,1,2)")
    int casCancel(@Param("id") Long id,@Param("uid") Long uid);
}
