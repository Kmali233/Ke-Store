package com.xiaoke.product.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_product_attr")
public class ProductAttr {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private String attrName;
    private String attrValue;
    private String attrGroup;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
