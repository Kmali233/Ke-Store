package com.xiaoke.order.vo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record OrderSummaryVO(String orderNo,Integer status,String statusDesc,BigDecimal totalAmount,LocalDateTime createTime) { }
