package com.xiaoke.order.vo;
import java.util.List;
public record OrderStatusVO(String orderNo,Integer status,String statusDesc,List<OrderStatusItemVO> items) { }
