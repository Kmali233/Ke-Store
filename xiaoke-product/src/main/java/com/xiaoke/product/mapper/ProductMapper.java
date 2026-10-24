package com.xiaoke.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaoke.product.entity.Product;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Set;

/**
 * 商品 Mapper
 */
public interface ProductMapper extends BaseMapper<Product> {
    String COLUMNS = "id,name,category_id,price,stock,sales AS order_count,image,description,status,create_time,update_time,deleted";
    String VISIBLE = """
        WHERE deleted=0 AND status=1 AND (category_id IS NULL
        <if test="visibleIds != null and !visibleIds.isEmpty()">
          OR category_id IN
          <foreach collection="visibleIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
        </if>)
        <if test="categoryId != null">AND category_id=#{categoryId}</if>
        <if test="pattern != null">AND name LIKE #{pattern} ESCAPE '!'</if>
        """;

    @Select("SELECT " + COLUMNS + " FROM t_product WHERE id=#{id}")
    Product findIncludingDeleted(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM t_product WHERE id=#{id} FOR UPDATE")
    Product lockById(@Param("id") Long id);

    @Select("<script>SELECT COUNT(*) FROM t_product " + VISIBLE + "</script>")
    long countVisible(@Param("visibleIds") Set<Long> visibleIds, @Param("categoryId") Long categoryId,
                      @Param("pattern") String pattern);

    @Select("<script>SELECT " + COLUMNS + " FROM t_product " + VISIBLE
            + " ORDER BY create_time DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Product> listVisible(@Param("visibleIds") Set<Long> visibleIds, @Param("categoryId") Long categoryId,
                              @Param("pattern") String pattern, @Param("offset") long offset, @Param("size") long size);

    @Select("SELECT COUNT(*) FROM t_product WHERE category_id=#{id} AND deleted=0")
    long countActiveByCategory(@Param("id") Long id);

    /** 白名单更新：SQL 根本不出现 stock / sales，避免覆盖订单正在维护的计数。 */
    @Update("""
        UPDATE t_product SET name=#{name},category_id=#{categoryId},price=#{price},
          image=#{image},description=#{description},status=#{status}
        WHERE id=#{id} AND deleted=0
        """)
    int updateEditableFields(Product product);

    @Update("UPDATE t_product SET deleted=1 WHERE id=#{id} AND deleted=0")
    int markDeleted(@Param("id") Long id);
}
