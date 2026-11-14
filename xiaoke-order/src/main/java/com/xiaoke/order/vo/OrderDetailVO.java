package com.xiaoke.order.vo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record OrderDetailVO(String orderNo, Integer status, String statusDesc, BigDecimal totalAmount,
                            String remark, LocalDateTime createTime, LocalDateTime payTime,
                            List<OrderItemVO> items) { }
