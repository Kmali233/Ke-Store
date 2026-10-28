package com.xiaoke.order.adapter.product;
import org.apache.ibatis.annotations.*;
public interface OrderSkuMapper {
    @Select("SELECT * FROM t_product_sku WHERE id=#{id}")
    OrderSkuRow find(@Param("id") Long id);
    @Select("SELECT * FROM t_product_sku WHERE product_id=#{pid} AND is_default=1")
    OrderSkuRow defaultSku(@Param("pid") Long pid);
    @Select("SELECT * FROM t_product_sku WHERE id=#{id} FOR UPDATE")
    OrderSkuRow lock(@Param("id") Long id);
    @Update("""
        UPDATE t_product_sku SET stock=stock-#{q},sales=sales+#{q}
        WHERE id=#{id} AND deleted=0 AND status=1 AND stock>=#{q} AND sales<=2147483647-#{q}
        """) int deduct(@Param("id") Long id,@Param("q") int quantity);
    @Update("""
        UPDATE t_product_sku SET stock=stock+#{q},sales=sales-#{q}
        WHERE id=#{id} AND stock<=2147483647-#{q} AND sales>=#{q}
        """) int restore(@Param("id") Long id,@Param("q") int quantity);
}
