package com.xiaoke.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaoke.product.entity.Category;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface CategoryMapper extends BaseMapper<Category> {
    @Select("SELECT * FROM t_category WHERE deleted=0")
    List<Category> listActive();

    @Select("SELECT * FROM t_category WHERE id=#{id}")
    Category findIncludingDeleted(@Param("id") Long id);

    @Select("SELECT * FROM t_category WHERE id=#{id} FOR UPDATE")
    Category lockById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM t_category WHERE parent_id=#{id} AND deleted=0")
    long countActiveChildren(@Param("id") Long id);

    @Update("""
        UPDATE t_category SET name=#{name},parent_id=#{parentId},icon=#{icon},
          sort=#{sort},status=#{status} WHERE id=#{id} AND deleted=0
        """)
    int updateEditableFields(Category category);

    @Update("UPDATE t_category SET deleted=1 WHERE id=#{id} AND deleted=0")
    int markDeleted(@Param("id") Long id);
}
