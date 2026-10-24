package com.xiaoke.order.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data @TableName("t_order_item")
public class OrderItem {
    @TableId(type=IdType.AUTO) private Long id;

    // 订单ID、商品ID
    private Long orderId,productId;
    // 订单编号、商品名称、商品图片
    private String orderNo,productName,productImage;
    // 商品价格、数量
    private BigDecimal price;
    private Integer quantity;
    // 删除标志
    @TableLogic private Integer deleted;
}
