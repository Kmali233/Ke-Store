package com.xiaoke.order.adapter.product;
import org.apache.ibatis.annotations.*;
/** 自有适配Mapper，不依赖product jar；恢复SQL有意包含软删和下架商品。 */
public interface OrderProductMapper {
    String COLUMNS="id,category_id,name,image,status,deleted";
    @Select("SELECT "+COLUMNS+" FROM t_product WHERE id=#{id}") OrderProductRow findById(@Param("id") Long id);
    @Select("SELECT "+COLUMNS+" FROM t_product WHERE id=#{id} FOR UPDATE") OrderProductRow selectForUpdate(@Param("id") Long id);
}
