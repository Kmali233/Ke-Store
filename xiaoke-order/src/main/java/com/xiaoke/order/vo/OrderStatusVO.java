package com.xiaoke.order.vo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record OrderStatusVO(String orderNo,Integer status,String statusDesc,String logisticsCompany,String trackingNo,List<OrderStatusItemVO> items) { }
