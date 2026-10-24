package com.xiaoke.order.vo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record OrderCreateVO(String orderNo,Integer status,BigDecimal totalAmount) { }
