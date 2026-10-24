package com.xiaoke.order.adapter.address;

/**
 * 用于装“这次订单采用的收货信息”
 * @param receiverName
 * @param receiverPhone
 * @param receiverAddress
 */
public record ShippingSnapshot(String receiverName,String receiverPhone,String receiverAddress) { }
