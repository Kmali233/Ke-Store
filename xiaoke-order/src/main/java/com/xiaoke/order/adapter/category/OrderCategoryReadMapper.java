package com.xiaoke.order.adapter.category;
import org.apache.ibatis.annotations.*;
public interface OrderCategoryReadMapper {
    @Select("SELECT id,parent_id,status,deleted FROM t_category WHERE id=#{id}")
    OrderCategoryRow findById(@Param("id") Long id);
    @Select("SELECT id,parent_id,status,deleted FROM t_category WHERE id=#{id} FOR UPDATE")
    OrderCategoryRow selectCategoryForUpdate(@Param("id") Long id);
}
