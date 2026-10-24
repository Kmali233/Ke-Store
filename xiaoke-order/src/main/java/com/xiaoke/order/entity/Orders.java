package com.xiaoke.order.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data @TableName("t_orders")
public class Orders {
    @TableId(type=IdType.AUTO) private Long id;
    // 订单编号、用户ID、总金额、状态、物流公司、跟踪编号、收货人、电话、地址、备注
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    private Integer status;
    private String logisticsCompany,trackingNo,receiverName,receiverPhone,receiverAddress,remark;
    private LocalDateTime payTime,shipTime,finishTime,createTime,updateTime;
    @TableLogic private Integer deleted;
}
