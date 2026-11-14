package com.xiaoke.order.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data @TableName("t_orders")
public class Orders {
    @TableId(type=IdType.AUTO) private Long id;
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    private Integer status;
    private String remark;
    private LocalDateTime payTime,createTime,updateTime;
    private LocalDateTime paymentExpiresAt,closeNextAttemptAt,closeFinishedAt;
    private String closeReason,closeToken;
    private Integer closeAttempts;
    @TableLogic private Integer deleted;

    public boolean paymentExpired(LocalDateTime nowUtc) {
        return paymentExpiresAt != null && !paymentExpiresAt.isAfter(nowUtc);
    }

    public boolean acceptsPayment(LocalDateTime nowUtc) {
        return closeReason == null && !paymentExpired(nowUtc);
    }
}
