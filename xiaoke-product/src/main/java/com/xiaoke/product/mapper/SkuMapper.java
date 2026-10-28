package com.xiaoke.product.mapper;
import com.xiaoke.product.entity.ProductSku;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.List;

public interface SkuMapper {
    @Select("SELECT * FROM t_product_sku WHERE product_id=#{pid} AND deleted=0 ORDER BY id")
    List<ProductSku> list(@Param("pid") Long pid);
    @Select("SELECT * FROM t_product_sku WHERE product_id=#{pid} AND is_default=1 FOR UPDATE")
    ProductSku lockDefault(@Param("pid") Long pid);
    @Select("SELECT * FROM t_product_sku WHERE id=#{id} AND product_id=#{pid} FOR UPDATE")
    ProductSku lock(@Param("pid") Long pid,@Param("id") Long id);
    @Insert("""
        INSERT INTO t_product_sku(product_id,name,spec_json,spec_hash,price,stock,sales,image,status,deleted,is_default)
        VALUES(#{productId},#{name},#{specJson},#{specHash},#{price},#{stock},#{sales},#{image},#{status},0,#{isDefault})
        """)
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(ProductSku row);
    @Update("""
        UPDATE t_product_sku SET name=#{name},spec_json=#{specJson},spec_hash=#{specHash},price=#{price},image=#{image},status=#{status}
        WHERE id=#{id} AND product_id=#{productId} AND deleted=0
        """) int update(ProductSku row);
    @Update("UPDATE t_product_sku SET deleted=1 WHERE id=#{id} AND product_id=#{pid} AND is_default=0 AND deleted=0")
    int delete(@Param("pid") Long pid,@Param("id") Long id);
    record Aggregate(BigDecimal price,Long stock,Long sales,Long invalid,Long defaults) { }
    @Select("""
        SELECT COALESCE(MIN(CASE WHEN status=1 AND deleted=0 THEN price END),0) price,
          COALESCE(SUM(CASE WHEN status=1 AND deleted=0 THEN stock ELSE 0 END),0) stock,
          COALESCE(SUM(sales),0) sales,
          COALESCE(SUM(CASE WHEN stock<0 OR sales<0 OR price<0 THEN 1 ELSE 0 END),0) invalid,
          COALESCE(SUM(CASE WHEN is_default=1 AND deleted=0 THEN 1 ELSE 0 END),0) defaults
        FROM t_product_sku WHERE product_id=#{pid}
        """) Aggregate aggregate(@Param("pid") Long pid);
}
