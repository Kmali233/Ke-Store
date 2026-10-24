package com.xiaoke.order.adapter.address;
// 这个目录集中放“订单模块访问其他业务数据所需的代码”


import com.xiaoke.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.stream.Stream;
import java.util.stream.Collectors;
@Component // 让 Spring 扫描并管理这个类的对象
@RequiredArgsConstructor  // 让 Lombok 自动生成必要参数的构造方法
public class OrderAddressReadAdapter {
    private final OrderAddressReadMapper mapper;

    // 读取、整理、检查收货信息
    public ShippingSnapshot readOwned(Long uid,Long aid) {
        /**
         * 调用 Mapper 查询本人地址。
         * 查不到，抛出 404“地址不存在”。
         * 把省、市、区、详细地址去掉首尾空格，再用空格拼接。
         * 检查收货人、电话、详细地址、完整地址是否为空或超长。
         * 创建并返回 ShippingSnapshot。
         */
        OrderAddressRow row=mapper.selectOwned(uid,aid);
        if (row==null) { throw new BusinessException(404,"地址不存在"); }
        String address=Stream.of(row.getProvince(),row.getCity(),row.getDistrict(),row.getDetailAddress())
                .filter(v->v!=null && !v.trim().isEmpty()).map(String::trim).collect(Collectors.joining(" "));
        if (!valid(row.getReceiverName(),50) || !valid(row.getReceiverPhone(),20)
                || !valid(row.getDetailAddress(),255) || !valid(address,255)) { throw new BusinessException(400,"收货信息不合法"); }
        return new ShippingSnapshot(row.getReceiverName().trim(),row.getReceiverPhone().trim(),address);
    }
    private boolean valid(String value,int max) {
        return value!=null && !value.trim().isEmpty()
                && value.trim().length()<=max;
    }
}
