package com.xiaoke.order.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

public interface CartMapper {
    record Line(Long skuId, Integer quantity) { }

    record CatalogLine(Long skuId, Integer quantity, Boolean selected, Long productId, String productName,
                       String skuName, String skuSpec, String image, BigDecimal price, Integer stock,
                       Integer skuStatus, Integer skuDeleted, Long categoryId, Integer productStatus,
                       Integer productDeleted) { }

    @Select("""
        SELECT c.sku_id, c.quantity, c.selected, s.product_id, p.name AS product_name,
               s.name AS sku_name, s.spec_json AS sku_spec, COALESCE(s.image, p.image) AS image,
               s.price, s.stock, s.status AS sku_status, s.deleted AS sku_deleted,
               p.category_id, p.status AS product_status, p.deleted AS product_deleted
        FROM t_cart_item c
        LEFT JOIN t_product_sku s ON s.id = c.sku_id
        LEFT JOIN t_product p ON p.id = s.product_id
        WHERE c.user_id = #{uid} ORDER BY c.sku_id
        """)
    List<CatalogLine> list(@Param("uid") Long uid);

    @Select("""
        SELECT sku_id, quantity FROM t_cart_item
        WHERE user_id = #{uid} AND selected = 1 ORDER BY sku_id LIMIT 21
        """)
    List<Line> selection(@Param("uid") Long uid);

    @Select("SELECT quantity FROM t_cart_item WHERE user_id = #{uid} AND sku_id = #{skuId}")
    Integer quantity(@Param("uid") Long uid, @Param("skuId") Long skuId);

    @Insert("INSERT INTO t_cart_item (user_id, sku_id, quantity) VALUES (#{uid}, #{skuId}, #{quantity})")
    int insert(@Param("uid") Long uid, @Param("skuId") Long skuId, @Param("quantity") int quantity);

    @Update("""
        UPDATE t_cart_item SET quantity = COALESCE(#{quantity,jdbcType=INTEGER}, quantity),
                               selected = COALESCE(#{selected,jdbcType=TINYINT}, selected)
        WHERE user_id = #{uid} AND sku_id = #{skuId}
        """)
    int update(@Param("uid") Long uid, @Param("skuId") Long skuId,
               @Param("quantity") Integer quantity, @Param("selected") Boolean selected);

    @Delete("DELETE FROM t_cart_item WHERE user_id = #{uid} AND sku_id = #{skuId}")
    int delete(@Param("uid") Long uid, @Param("skuId") Long skuId);

    @Delete("DELETE FROM t_cart_item WHERE user_id = #{uid} AND selected = 1")
    int clearSelection(@Param("uid") Long uid);
}
