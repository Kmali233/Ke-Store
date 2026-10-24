package com.xiaoke.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaoke.product.entity.ProductAttr;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ProductAttrMapper extends BaseMapper<ProductAttr> {
    @Select("SELECT * FROM t_product_attr WHERE product_id=#{id} AND deleted=0 ORDER BY sort ASC,id ASC")
    List<ProductAttr> selectActiveByProductId(@Param("id") Long id);

    @Update("UPDATE t_product_attr SET deleted=1 WHERE product_id=#{id} AND deleted=0")
    int softDeleteByProductId(@Param("id") Long id);
}
