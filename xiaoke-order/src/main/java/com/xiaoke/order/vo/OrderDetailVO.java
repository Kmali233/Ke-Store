package com.xiaoke.order.vo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record OrderDetailVO(String orderNo,Integer status,String statusDesc,BigDecimal totalAmount,String receiverName,String receiverPhone,String receiverAddress,String remark,String logisticsCompany,String trackingNo,LocalDateTime createTime,LocalDateTime payTime,LocalDateTime shipTime,LocalDateTime finishTime,List<OrderItemVO> items) { }
